package org.rakam.analysis.webhook;

import com.fasterxml.jackson.core.JsonGenerator;
import io.airlift.log.Logger;
import io.airlift.slice.DynamicSliceOutput;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.cookie.Cookie;
import okhttp3.*;
import org.rakam.collection.Event;
import org.rakam.collection.EventList;
import org.rakam.plugin.EventMapper;
import org.rakam.util.JsonHelper;

import javax.inject.Inject;
import java.io.DataOutput;
import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;

public class WebhookEventMapper implements EventMapper {
    private final static Logger LOGGER = Logger.get(WebhookEventMapper.class);

    private final OkHttpClient asyncHttpClient;
    private final int timeoutInMillis = 20000;
    private final Queue<Event> queue = new ConcurrentLinkedQueue<>();
    private final DynamicSliceOutput slice;
    private final AtomicInteger counter;

    @Inject
    public WebhookEventMapper(WebhookConfig config) {
        this.asyncHttpClient = new OkHttpClient.Builder()
                .connectTimeout(timeoutInMillis, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutInMillis, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutInMillis, TimeUnit.MILLISECONDS)
                .build();
        slice = new DynamicSliceOutput(100);
        counter = new AtomicInteger();

        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            try {
                int size = counter.get();
                if (size == 0) {
                    return;
                }

                JsonGenerator generator = JsonHelper.getMapper().getFactory().createGenerator((DataOutput) slice);

                generator.writeStartObject();
                generator.writeFieldName("activities");

                generator.writeStartArray();

                int i = 0;
                for (; i < size; i++) {
                    Event event = queue.poll();
                    if (event == null) {
                        break;
                    }
                    generator.writeStartObject();

                    generator.writeFieldName("timestamp");
                    Object time = event.getAttribute("_time");
                    generator.writeNumber(((Number) time).longValue());

                    generator.writeFieldName("userId");
                    Object user = event.getAttribute("_user");
                    generator.writeString(((String) user));

                    generator.writeFieldName("sessionId");
                    Object session = event.getAttribute("_session_id");
                    generator.writeString(((String) session));

                    generator.writeFieldName("deviceId");
                    Object device = event.getAttribute("_device_id");
                    generator.writeString(((String) device));

                    generator.writeFieldName("host");
                    Object host = event.getAttribute("__ip");
                    generator.writeString(((String) host));

                    generator.writeEndObject();
                }

                generator.writeEndArray();
                generator.writeEndObject();

                generator.flush();
                byte[] base = (byte[]) slice.getUnderlyingSlice().getBase();
                MediaType mediaType = MediaType.parse("application/json");
                Request.Builder builder = new Request.Builder().url(config.getUrl());
                if (config.getHeaders() != null) {
                    for (Map.Entry<String, String> entry : config.getHeaders().entrySet()) {
                        builder.addHeader(entry.getKey(), entry.getValue());
                    }
                }

                Request build = builder.post(RequestBody.create(mediaType, base, 0, slice.size())).build();
                tryOperation(build, 2);
                counter.addAndGet(-i);
            } catch (IOException e) {
                LOGGER.warn(e, "");
                slice.reset();
            }
        }, 5, 5, SECONDS);
    }

    private void tryOperation(Request build, int retryCount) throws IOException {
        Response execute = null;

        try {
            execute = asyncHttpClient.newCall(build).execute();

            if (execute.code() != 200) {
                if (retryCount > 0) {
                    tryOperation(build, retryCount - 1);
                    return;
                }

                LOGGER.warn(new RuntimeException(execute.body().string()), "Unable to execute Webhook request");
            }
        } finally {
            if (execute != null) {
                execute.close();
            }
            slice.reset();
        }
    }

    @Override
    public CompletableFuture<List<Cookie>> mapAsync(Event event, RequestParams requestParams, InetAddress sourceAddress, HttpHeaders responseHeaders) {
        queue.add(event);
        counter.incrementAndGet();
        return COMPLETED_EMPTY_FUTURE;
    }

    @Override
    public CompletableFuture<List<Cookie>> mapAsync(EventList events, RequestParams requestParams, InetAddress sourceAddress, HttpHeaders responseHeaders) {
        queue.addAll(events.events);
        counter.addAndGet(events.events.size());
        return COMPLETED_EMPTY_FUTURE;
    }
}
