/*
 * Copyright (c) Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 */
package io.pravega.flinktools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pravega.client.stream.StreamCut;
import io.pravega.connectors.flink.FlinkPravegaReader;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.common.serialization.SimpleStringEncoder;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.fs.Path;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.streaming.api.functions.sink.filesystem.StreamingFileSink;
import org.apache.flink.streaming.api.functions.sink.filesystem.rollingpolicies.OnCheckpointRollingPolicy;
import org.apache.flink.util.Collector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Copy a Pravega stream to a set of files on any Flink file system, including S3.
 * This uses Flink to provide exactly-once, recovery from failures, and parallelism.
 * The stream is assumed to contain UTF-8 strings.
 * When written to files, each event will be followed by a new line.
 */
public class StreamToFileJob extends AbstractJob {
    final private static Logger log = LoggerFactory.getLogger(StreamToFileJob.class);

    /**
     * The entry point for Flink applications.
     *
     * @param args Command line arguments
     */
    public static void main(String... args) {
        AppConfiguration config = new AppConfiguration(args);
        log.info("config: {}", config);
        StreamToFileJob job = new StreamToFileJob(config);
        job.run();
    }

    public StreamToFileJob(AppConfiguration appConfiguration) {
        super(appConfiguration);
    }

    public void run() {
        try {
            final String jobName = getConfig().getJobName(StreamToFileJob.class.getName());
            final AppConfiguration.StreamConfig inputStreamConfig = getConfig().getStreamConfig("input");
            log.info("input stream: {}", inputStreamConfig);
            final String outputFilePath = getConfig().getParams().getRequired("output");
            log.info("output file: {}", outputFilePath);
            createStream(inputStreamConfig);
            final StreamCut startStreamCut = resolveStartStreamCut(inputStreamConfig);
            final StreamCut endStreamCut = resolveEndStreamCut(inputStreamConfig);
            final StreamExecutionEnvironment env = initializeFlinkStreaming();

            final FlinkPravegaReader<String> flinkPravegaReader = FlinkPravegaReader.<String>builder()
                    .withPravegaConfig(inputStreamConfig.getPravegaConfig())
                    .forStream(inputStreamConfig.getStream(), startStreamCut, endStreamCut)
                    .withDeserializationSchema(new SimpleStringSchema())
                    .build();
            final DataStream<String> lines = env
                    .addSource(flinkPravegaReader)
                    .uid("pravega-reader")
                    .name("pravega-reader");

            final ObjectMapper objectMapper = new ObjectMapper();
            final DataStream<Tuple3<String,String,Long>> withDups = lines.flatMap(new FlatMapFunction<String, Tuple3<String,String,Long>>() {
                @Override
                public void flatMap(String s, Collector<Tuple3<String,String,Long>> collector) throws Exception {
                    final JsonNode node = objectMapper.readTree(s);
                    collector.collect(Tuple3.of(s, node.get("sensorId").asText(), node.get("timestamp").asLong()));
                }
            });
            final DataStream<String> withoutDups = withDups
                    .keyBy(t -> t.f1)
                    .process(new KeyedProcessFunction<String, Tuple3<String, String, Long>, Tuple3<String, String, Long>>() {
                        private ValueState<Long> maxCounterState;

                        @Override
                        public void open(Configuration parameters) throws Exception {
                            maxCounterState = getRuntimeContext().getState(new ValueStateDescriptor<Long>("maxCounter", Long.class));
                        }

                        @Override
                        public void processElement(Tuple3<String, String, Long> value, Context ctx, Collector<Tuple3<String, String, Long>> out) throws Exception {
                            final long counter = value.f2;
                            final Long maxCounter = maxCounterState.value();
                            if (maxCounter == null || maxCounter < counter) {
                                maxCounterState.update(counter);
                                out.collect(value);
                            } else {
                                log.info("Dropping record with decreasing counter {} and key {}", counter, ctx.getCurrentKey());
                            }
                        }
                    })
                    .map(t -> t.f0);
            final DataStream<String> toOutput = withoutDups;
            toOutput.printToErr();

            final StreamingFileSink<String> sink = StreamingFileSink
                    .forRowFormat(new Path(outputFilePath), new SimpleStringEncoder<String>())
                    .withRollingPolicy(OnCheckpointRollingPolicy.build())
                    .build();
            toOutput.addSink(sink)
                .uid("file-sink")
                .name("file-sink");

            log.info("Executing {} job", jobName);
            env.execute(jobName);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
