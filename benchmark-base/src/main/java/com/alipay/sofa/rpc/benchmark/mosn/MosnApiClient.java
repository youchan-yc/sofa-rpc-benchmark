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
package com.alipay.sofa.rpc.benchmark.mosn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * HTTP client for mosn3 sidecar API.
 *
 * Provides methods to:
 * <ul>
 *   <li>{@link #configApplication} — initialize the registry client (POST /configs/application)</li>
 *   <li>{@link #publishService} — register a service (POST /services/publish)</li>
 *   <li>{@link #subscribeService} — subscribe to a service and get endpoints (POST /services/subscribe)</li>
 * </ul>
 *
 * The mosn3 API base URL is configured via system property {@code mosn.api.address}
 * (default: {@code http://127.0.0.1:13330}).
 */
public class MosnApiClient {

    private static final Logger LOGGER                   = LoggerFactory.getLogger(MosnApiClient.class);

    private static final String DEFAULT_MOSN_API_ADDRESS = "http://127.0.0.1:13330";
    private static final int    CONNECT_TIMEOUT_MS       = 5000;
    private static final int    READ_TIMEOUT_MS          = 10000;

    private final String        baseUrl;

    public MosnApiClient() {
        this(System.getProperty("mosn.api.address", DEFAULT_MOSN_API_ADDRESS));
    }

    public MosnApiClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * Initialize the mosn3 registry client.
     *
     * @param appName application name
     */
    public void configApplication(String appName) {
        String body = "{\"appName\":\"" + appName + "\"}";
        String response = doPost("/configs/application", body);
        LOGGER.info("mosn3 configApplication response: {}", response);
    }

    /**
     * Publish (register) a service to the registry via mosn3.
     *
     * @param serviceName service interface name (e.g. "com.alipay.sofa.rpc.benchmark.service.UserService")
     * @param group       service group (empty string for default)
     * @param host        service host (e.g. "127.0.0.1")
     * @param port        service port (e.g. 12200)
     * @param protocol    protocol name (e.g. "bolt", "tri")
     */
    public void publishService(String serviceName, String group, String host, int port, String protocol) {
        String body = "{\"serviceName\":\"" + serviceName + ":1.0" + "\""
            + ",\"group\":\"" + group + "\""
            + ",\"host\":\"" + host + "\""
            + ",\"port\":" + port
            + ",\"metadata\":{\"protocol\":\"" + protocol + "\",\"healthCheckPath\":\"\"}"
            + "}";
        String response = doPost("/services/publish", body);
        LOGGER.info("mosn3 publishService response: serviceName={}, response={}", serviceName, response);
    }

    /**
     * Subscribe to a service via mosn3 and return the list of endpoint addresses.
     *
     * The returned addresses are already replaced by mosn3 to point to the local
     * outbound proxy (e.g. "localhost:12220").
     *
     * @param serviceName service interface name
     * @param group       service group (empty string for default)
     * @return list of endpoint addresses (e.g. ["localhost:12220"])
     */
    public List<SubscribeEndpoint> subscribeService(String serviceName, String group) {
        String body = "{\"serviceName\":\"" + serviceName + "\",\"group\":\"" + group + "\"}";
        String response = doPost("/services/subscribe", body);
        LOGGER.info("mosn3 subscribeService response: serviceName={}, response={}", serviceName, response);
        return parseSubscribeEndpoints(response);
    }

    private String doPost(String path, String jsonBody) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(baseUrl + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);

            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            }

            int responseCode = connection.getResponseCode();
            StringBuilder responseBuilder = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                    responseCode >= 400 ? connection.getErrorStream() : connection.getInputStream(),
                    StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    responseBuilder.append(line);
                }
            }

            String responseBody = responseBuilder.toString();
            if (responseCode >= 400) {
                throw new RuntimeException("mosn3 API call failed: path=" + path
                    + ", status=" + responseCode + ", body=" + responseBody);
            }
            return responseBody;
        } catch (IOException e) {
            throw new RuntimeException("mosn3 API call failed: path=" + path, e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Minimal JSON parsing for subscribe response without external dependencies.
     *
     * Expected format:
     * {"code":0,"message":"ok","endpoints":[{"address":"localhost:12220","weight":1,"metadata":{...}}]}
     */
    private List<SubscribeEndpoint> parseSubscribeEndpoints(String json) {
        if (json == null || json.isEmpty()) {
            return Collections.emptyList();
        }

        // Check code field
        int codeIndex = json.indexOf("\"code\"");
        if (codeIndex < 0) {
            LOGGER.warn("mosn3 subscribe response missing 'code' field: {}", json);
            return Collections.emptyList();
        }

        // Find endpoints array
        int endpointsStart = json.indexOf("\"endpoints\"");
        if (endpointsStart < 0) {
            LOGGER.warn("mosn3 subscribe response missing 'endpoints' field: {}", json);
            return Collections.emptyList();
        }

        // Find the array brackets
        int arrayStart = json.indexOf('[', endpointsStart);
        if (arrayStart < 0) {
            return Collections.emptyList();
        }

        // Find matching closing bracket
        int arrayEnd = findMatchingBracket(json, arrayStart);
        if (arrayEnd < 0) {
            return Collections.emptyList();
        }

        String arrayContent = json.substring(arrayStart + 1, arrayEnd);
        if (arrayContent.trim().isEmpty()) {
            return Collections.emptyList();
        }

        List<SubscribeEndpoint> endpoints = new ArrayList<>();
        // Split by each endpoint object
        int depth = 0;
        int objectStart = -1;
        for (int i = 0; i < arrayContent.length(); i++) {
            char ch = arrayContent.charAt(i);
            if (ch == '{') {
                if (depth == 0) {
                    objectStart = i;
                }
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0 && objectStart >= 0) {
                    String objectStr = arrayContent.substring(objectStart, i + 1);
                    SubscribeEndpoint endpoint = parseEndpointObject(objectStr);
                    if (endpoint != null) {
                        endpoints.add(endpoint);
                    }
                    objectStart = -1;
                }
            }
        }
        return endpoints;
    }

    private SubscribeEndpoint parseEndpointObject(String json) {
        String address = extractStringField(json, "address");
        if (address == null || address.isEmpty()) {
            return null;
        }
        int weight = extractIntField(json, "weight", 1);
        return new SubscribeEndpoint(address, weight);
    }

    private String extractStringField(String json, String fieldName) {
        String key = "\"" + fieldName + "\"";
        int keyIndex = json.indexOf(key);
        if (keyIndex < 0) {
            return null;
        }
        int colonIndex = json.indexOf(':', keyIndex + key.length());
        if (colonIndex < 0) {
            return null;
        }
        int valueStart = json.indexOf('"', colonIndex + 1);
        if (valueStart < 0) {
            return null;
        }
        int valueEnd = json.indexOf('"', valueStart + 1);
        if (valueEnd < 0) {
            return null;
        }
        return json.substring(valueStart + 1, valueEnd);
    }

    private int extractIntField(String json, String fieldName, int defaultValue) {
        String key = "\"" + fieldName + "\"";
        int keyIndex = json.indexOf(key);
        if (keyIndex < 0) {
            return defaultValue;
        }
        int colonIndex = json.indexOf(':', keyIndex + key.length());
        if (colonIndex < 0) {
            return defaultValue;
        }
        int start = colonIndex + 1;
        while (start < json.length() && json.charAt(start) == ' ') {
            start++;
        }
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        if (end == start) {
            return defaultValue;
        }
        return Integer.parseInt(json.substring(start, end));
    }

    private int findMatchingBracket(String json, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '[') {
                depth++;
            } else if (ch == ']') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Represents an endpoint returned by mosn3 subscribe API.
     */
    public static class SubscribeEndpoint {
        private final String address;
        private final int    weight;

        public SubscribeEndpoint(String address, int weight) {
            this.address = address;
            this.weight = weight;
        }

        /**
         * @return endpoint address, e.g. "localhost:12220"
         */
        public String getAddress() {
            return address;
        }

        /**
         * @return endpoint weight
         */
        public int getWeight() {
            return weight;
        }

        /**
         * Extract host from address (part before ':').
         */
        public String getHost() {
            int colonIndex = address.indexOf(':');
            return colonIndex > 0 ? address.substring(0, colonIndex) : address;
        }

        /**
         * Extract port from address (part after ':').
         */
        public int getPort() {
            int colonIndex = address.indexOf(':');
            return colonIndex > 0 ? Integer.parseInt(address.substring(colonIndex + 1)) : -1;
        }

        @Override
        public String toString() {
            return "SubscribeEndpoint{address='" + address + "', weight=" + weight + "}";
        }
    }
}
