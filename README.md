# gabx-lambda-runtime

Biblioteca para execução de AWS Lambda com custom runtime (`provided.al2` ou `provided.al2023`) usando GraalVM Native Image.
Loop manual da Runtime API com delegação para um único handler com I/O String.

## Contrato público

```java
public interface RequestHandler {
    Response handle(String eventJson, RawContext ctx) throws Exception;
}

public record Response(int statusCode, String body) {}
public record Context(String requestId, long deadlineMs, String invokedArn, String traceId) {}
```

## Política por gatilho

API: FAIL_ALL + wrap automático HTTP  
SQS: ONE_CALL_PER_RECORD + PARTIAL_FAIL  
SNS/EventBridge/S3/...: ACK vazio + FAIL_ALL em erro

## Build nativo da Lambda

```bash
mvn -Pnative -DskipTests native:compile
cp target/*bootstrap* bootstrap || cp target/* bootstrap
chmod +x bootstrap
zip function.zip bootstrap
```

## Deploy (Terraform)

```hcl
runtime = "provided.al2"
handler = "bootstrap"
filename = "function.zip"
```

## Invariantes

- Input sempre String JSON bruto
- Response obrigatório para API
- SQS processado por record
- Sem reflection, compatível com native
