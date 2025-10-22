package br.com.gabxdev.lambda.runtime;

public interface RequestHandler {
    /**
     * @param eventJson string bruta do evento (API Gateway v1/v2, SQS, SNS, S3, etc)
     * @param ctx       metadados úteis (requestId, arn, deadline, traceId)
     * @return Response
     */
    Response handle(String eventJson, Context ctx) throws Exception;
}
