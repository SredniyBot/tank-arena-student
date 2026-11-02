package edu.arena.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Cancels the transfer as soon as the byte limit is exceeded, before JSON decoding. */
final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final int maximum;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private Flow.Subscription subscription;

    LimitedBodySubscriber(int maximum) {
        this.maximum = maximum;
    }

    public CompletionStage<byte[]> getBody() {
        return result;
    }

    public void onSubscribe(Flow.Subscription value) {
        if (subscription != null) {
            value.cancel();
            return;
        }
        subscription = value;
        value.request(1);
    }

    public void onNext(List<ByteBuffer> buffers) {
        for (ByteBuffer buffer : buffers) {
            if (buffer.remaining() > maximum - bytes.size()) {
                subscription.cancel();
                result.completeExceptionally(
                        new IOException("Server response exceeds the configured byte limit"));
                return;
            }
            byte[] chunk = new byte[buffer.remaining()];
            buffer.get(chunk);
            bytes.writeBytes(chunk);
        }
        subscription.request(1);
    }

    public void onError(Throwable error) {
        result.completeExceptionally(error);
    }

    public void onComplete() {
        result.complete(bytes.toByteArray());
    }
}
