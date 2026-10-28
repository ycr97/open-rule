package io.openrule.spring.fact;

import java.io.ByteArrayOutputStream;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;

/** Query-only POST. No application retry, redirect or fallback. All bytes and parsing use the call budget. */
public final class HttpFactProvider implements FactProvider {
    private final SecretResolver secrets;
    public HttpFactProvider(SecretResolver secrets) {
        this.secrets = Objects.requireNonNull(secrets);
        // JDK caches these flags at first HTTP use; the host must set them at JVM startup.
        if (!"true".equalsIgnoreCase(System.getProperty("jdk.httpclient.disableRetryConnect"))
                || !"false".equalsIgnoreCase(System.getProperty("jdk.httpclient.enableAllMethodRetry")))
            throw new IllegalStateException("HTTP facts require JVM startup flags: "
                    + "-Djdk.httpclient.disableRetryConnect=true -Djdk.httpclient.enableAllMethodRetry=false");
    }
    @Override public String type() { return "HTTP"; }
    @Override public FactBatchResult resolve(FactBatchRequest request) {
        byte[] body;
        try { body = FactTransportSupport.encode(FactTransportSupport.requestBody(request)); }
        catch (RuntimeException ex) { return FactBatchResult.failed(request, "OR-FACT-REQUEST-INPUT", false); }
        var settings = request.provider().http();
        String secret = null;
        if (settings.secretRef() != null) {
            try { secret = secrets.resolve(settings.secretRef()); }
            catch (RuntimeException ex) { return FactBatchResult.failed(request, "OR-FACT-SECRET", false); }
            if (secret == null || secret.isBlank() || secret.indexOf('\r') >= 0 || secret.indexOf('\n') >= 0)
                return FactBatchResult.failed(request, "OR-FACT-SECRET", false);
        }
        if (request.deadline().failureCode() != null || request.remainingNanos() == 0)
            return FactBatchResult.failed(request, "OR-FACT-TIMEOUT", false);
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofNanos(Math.max(1, Math.min(request.remainingNanos(),
                        TimeUnit.MILLISECONDS.toNanos(settings.connectTimeoutMillis()))))).build();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(settings.endpoint())
                    .timeout(Duration.ofNanos(Math.max(1, request.remainingNanos())))
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            if (secret != null) builder.header("Authorization", "Bearer " + secret);
            if (request.deadline().failureCode() != null || request.remainingNanos() == 0)
                return FactBatchResult.failed(request, "OR-FACT-TIMEOUT", false);
            request.attempt().markStarted();
            HttpResponse<byte[]> response = client.send(builder.build(), ignored -> new LimitedBody());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                return FactBatchResult.failed(request, "OR-FACT-HTTP", true);
            try { return FactTransportSupport.response(request, FactTransportSupport.parse(response.body())); }
            catch (RuntimeException ex) { return FactBatchResult.failed(request, "OR-FACT-RESPONSE", true); }
        } catch (HttpTimeoutException ex) {
            return FactBatchResult.failed(request, "OR-FACT-TIMEOUT", true);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return FactBatchResult.failed(request, "OR-EXECUTION-CANCELLED", request.attempt().started());
        } catch (Exception ex) {
            boolean limit = false;
            for (Throwable cause = ex; cause != null; cause = cause.getCause()) if (cause instanceof BodyLimit) limit = true;
            return FactBatchResult.failed(request, limit ? "OR-FACT-RESPONSE" : "OR-FACT-HTTP", request.attempt().started());
        } finally { client.shutdownNow(); }
    }
    private static final class BodyLimit extends RuntimeException { }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription; subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if ((long) bytes.size() + buffer.remaining() > FactTransportSupport.MAX_BYTES) {
                    subscription.cancel(); result.completeExceptionally(new BodyLimit()); return;
                }
                byte[] part = new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
