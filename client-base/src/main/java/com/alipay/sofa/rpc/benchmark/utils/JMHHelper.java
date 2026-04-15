/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alipay.sofa.rpc.benchmark.utils;

import com.alipay.sofa.common.utils.StringUtil;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.text.SimpleDateFormat;
import java.util.Date;

public class JMHHelper {

    public static ChainedOptionsBuilder newBaseChainedOptionsBuilder(String[] args) throws ParseException {
        Options options = new Options();
        options.addOption(Option.builder().longOpt("warmupIterations").hasArg().build());
        options.addOption(Option.builder().longOpt("warmupTime").hasArg().build());
        options.addOption(Option.builder().longOpt("measurementIterations").hasArg().build());
        options.addOption(Option.builder().longOpt("measurementTime").hasArg().build());
        options.addOption(Option.builder().longOpt("forks").hasArg().build());
        options.addOption(Option.builder().longOpt("enableGcProfiler").hasArg().build());
        CommandLineParser parser = new DefaultParser();
        CommandLine line = parser.parse(options, args);
        int warmupIterations = Integer.parseInt(line.getOptionValue("warmupIterations", "3"));
        int warmupTime = Integer.parseInt(line.getOptionValue("warmupTime", "10"));
        int measurementIterations = Integer.parseInt(line.getOptionValue("measurementIterations", "3"));
        int measurementTime = Integer.parseInt(line.getOptionValue("measurementTime", "60"));
        int forks = Integer.parseInt(line.getOptionValue("forks", "1"));
        boolean enableGcProfiler = Boolean.parseBoolean(line.getOptionValue("enableGcProfiler", "true"));
        String format = System.getProperty("result.format", "JSON");
        String output = System.getProperty("benchmark.output");
        ChainedOptionsBuilder optBuilder = new OptionsBuilder()
            .warmupIterations(warmupIterations)
            .warmupTime(TimeValue.seconds(warmupTime))
            .measurementIterations(measurementIterations)
            .measurementTime(TimeValue.seconds(measurementTime))
            .forks(forks)
            .timeout(TimeValue.minutes(30));
        if (enableGcProfiler) {
            optBuilder.addProfiler(GCProfiler.class);
        }
        if (StringUtil.isNotBlank(format)) {
            ResultFormatType resultFormatType = ResultFormatType.valueOf(format.toUpperCase());
            optBuilder.resultFormat(resultFormatType);
            String suffix = resultFormatType == ResultFormatType.JSON ? ".json"
                : resultFormatType == ResultFormatType.CSV ? ".csv" : ".txt";
            String timestamp = new SimpleDateFormat("yyyyMMddHHmm").format(new Date());
            optBuilder.result("jmh-result-" + timestamp + suffix);
        }
        if (StringUtil.isNotBlank(output)) {
            optBuilder.output(output);
        }
        return optBuilder;
    }
}
