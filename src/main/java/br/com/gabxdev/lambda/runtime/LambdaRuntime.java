package br.com.gabxdev.lambda.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loop manual do Runtime API (compatível com native + provided.al2/al2023).
 * - API v1/v2: embrulha Response em {statusCode, headers: {"Content-Type":"application/json"}, body, isBase64Encoded:false}
 * - SQS: one-call-per-record + partial failure
 * - Outros (SNS/EventBridge/S3/...): chama handler 1x e responde ACK {}
 */
public final class LambdaRuntime {
    private LambdaRuntime() {
    }

    private static final ObjectMapper OM = new ObjectMapper();

    public static void start(RequestHandler handler) {
        final String runtimeApi = System.getenv("AWS_LAMBDA_RUNTIME_API");
        if (runtimeApi == null || runtimeApi.isBlank()) {
            System.err.println("AWS_LAMBDA_RUNTIME_API ausente. Este binário deve rodar dentro da AWS Lambda.");
            System.exit(2);
        }

        final String base = "http://" + runtimeApi + "/2018-06-01/runtime";
        final HttpClient http = HttpClient.newHttpClient();

        while (true) {
            String requestId = null;
            try {
                var next = http.send(HttpRequest.newBuilder(URI.create(base + "/invocation/next"))
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());

                var headers = next.headers();
                requestId = headers.firstValue("Lambda-Runtime-Aws-Request-Id").orElseThrow();
                long deadline = headers.firstValue("Lambda-Runtime-Deadline-Ms").map(Long::parseLong).orElse(0L);
                String arn = headers.firstValue("Lambda-Runtime-Invoked-Function-Arn").orElse("");
                String traceId = headers.firstValue("Lambda-Runtime-Trace-Id").orElse("");

                String eventJson = next.body();
                JsonNode root = OM.readTree(eventJson);

                EventType type = EventClassifier.classify(root);

                byte[] payload;
                switch (type) {
                    case API_V2, API_V1 -> {
                        Response r = handler.handle(eventJson, new Context(requestId, deadline, arn, traceId));
                        payload = AutoResponses.api(r);
                    }
                    case SQS -> {
                        payload = handleSqsBatchPerRecord(handler, eventJson, root, requestId, deadline, arn, traceId);
                    }
                    default -> {
                        try {
                            handler.handle(eventJson, new Context(requestId, deadline, arn, traceId));
                        } catch (Throwable t) {
                            postInvokeError(http, base, requestId, t);
                            continue;
                        }
                        payload = AutoResponses.ack();
                    }
                }

                http.send(HttpRequest.newBuilder(URI.create(base + "/invocation/" + requestId + "/response"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                                .build(),
                        HttpResponse.BodyHandlers.discarding());

            } catch (Throwable t) {
                if (requestId == null) {
                    postInitError(http, base, t);
                } else {
                    postInvokeError(http, base, requestId, t);
                }
            }
        }
    }

    private static byte[] handleSqsBatchPerRecord(
            RequestHandler handler, String fullEventJson, JsonNode root,
            String requestId, long deadline, String arn, String traceId) throws Exception {

        var records = root.path("Records");
        if (!records.isArray() || records.size() == 0) {
            return AutoResponses.ack();
        }

        List<String> failures = new ArrayList<>();

        for (int i = 0; i < records.size(); i++) {
            JsonNode rec = records.get(i);
            String messageId = rec.path("messageId").asText(""); // necessário para partial failure
            try {
                String recordJson = rec.toString();
                handler.handle(recordJson, new Context(requestId, deadline, arn, traceId));
            } catch (Throwable ex) {
                if (!messageId.isEmpty()) failures.add(messageId);
            }
        }

        return AutoResponses.sqsPartialFailures(failures);
    }

    private static void postInitError(HttpClient http, String base, Throwable t) {
        try {
            var b = OM.writeValueAsBytes(Map.of(
                    "errorMessage", String.valueOf(t.getMessage()),
                    "errorType", t.getClass().getName()
            ));
            http.send(HttpRequest.newBuilder(URI.create(base + "/init/error"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(b)).build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignore) {
        }
        System.exit(1);
    }

    private static void postInvokeError(HttpClient http, String base, String requestId, Throwable t) {
        try {
            var b = OM.writeValueAsBytes(Map.of(
                    "errorMessage", String.valueOf(t.getMessage()),
                    "errorType", t.getClass().getName()
            ));
            http.send(HttpRequest.newBuilder(URI.create(base + "/invocation/" + requestId + "/error"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(b)).build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignore) {
        }
    }

    enum EventType {API_V1, API_V2, SQS, SNS, EVENTBRIDGE, S3, KINESIS, DYNAMODB, UNKNOWN}

    static final class EventClassifier {
        static EventType classify(JsonNode root) {
            if (root.path("requestContext").path("http").has("method")) return EventType.API_V2;
            if (root.has("httpMethod")) return EventType.API_V1;

            JsonNode recs = root.path("Records");
            if (recs.isArray() && recs.size() > 0) {
                JsonNode r0 = recs.get(0);
                String src = r0.path("eventSource").asText("");
                if ("aws:sqs".equals(src)) return EventType.SQS;
                if ("aws:sns".equals(src) || r0.has("Sns")) return EventType.SNS;
                if (r0.has("s3")) return EventType.S3;
                if ("aws:kinesis".equals(src)) return EventType.KINESIS;
                if ("aws:dynamodb".equals(src)) return EventType.DYNAMODB;
            }

            if (root.has("detail-type") && root.has("source")) return EventType.EVENTBRIDGE;

            return EventType.UNKNOWN;
        }
    }

    static final class AutoResponses {
        private static final ObjectMapper OM = new ObjectMapper();

        static byte[] api(Response r) throws Exception {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("statusCode", r == null ? 200 : r.statusCode());
            map.put("headers", Map.of("Content-Type", "application/json"));
            map.put("body", r == null ? "" : (r.body() == null ? "" : r.body()));
            map.put("isBase64Encoded", false);
            return OM.writeValueAsBytes(map);
        }

        static byte[] sqsPartialFailures(List<String> failedIds) throws Exception {
            if (failedIds == null || failedIds.isEmpty()) {
                return ack(); // todos ok
            }
            List<Map<String, String>> items = new ArrayList<>(failedIds.size());
            for (String id : failedIds) items.add(Map.of("itemIdentifier", id));
            Map<String, Object> body = Map.of("batchItemFailures", items);
            return OM.writeValueAsBytes(body);
        }

        static byte[] ack() throws Exception {
            return OM.writeValueAsBytes(Map.of());
        }
    }
}