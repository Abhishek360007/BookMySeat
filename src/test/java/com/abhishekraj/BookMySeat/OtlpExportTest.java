package com.abhishekraj.BookMySeat;

import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.metrics.v1.Metric;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

// Metrics leave the app the way Grafana Cloud expects them: OTLP over HTTP, gzipped, with the auth header.
// A tiny HTTP server stands in for the OTLP gateway.
@SpringBootTest(properties = {
		"management.otlp.metrics.export.enabled=true",
		"management.otlp.metrics.export.step=PT1S",
		"management.otlp.metrics.export.headers.Authorization=Basic dGVzdDp0b2tlbg=="
})
@AutoConfigureMetrics // tests normally switch exporters off
@Import(TestcontainersConfig.class)
class OtlpExportTest {

	record Received(String authorization, String encoding, byte[] body) {}

	static final BlockingQueue<Received> received = new LinkedBlockingQueue<>();
	static final HttpServer gateway = startGateway();

	static HttpServer startGateway() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			server.createContext("/otlp/v1/metrics", exchange -> {
				try (InputStream body = exchange.getRequestBody()) {
					received.add(new Received(exchange.getRequestHeaders().getFirst("Authorization"),
							exchange.getRequestHeaders().getFirst("Content-Encoding"), body.readAllBytes()));
				}
				exchange.sendResponseHeaders(200, -1);
				exchange.close();
			});
			server.start();
			return server;
		} catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	@DynamicPropertySource
	static void gatewayUrl(DynamicPropertyRegistry registry) {
		registry.add("management.otlp.metrics.export.url",
				() -> "http://localhost:" + gateway.getAddress().getPort() + "/otlp/v1/metrics");
	}

	@AfterAll
	static void stopGateway() {
		gateway.stop(0);
	}

	@Test
	void pushesGzippedOtlpWithTheAuthHeader_includingBusinessMetrics() throws Exception {
		// The first pushes can come before the JVM metrics are registered: wait for one that has them
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		Received push;
		ExportMetricsServiceRequest request;
		List<String> names;
		do {
			push = received.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
			assertThat(push).as("no OTLP push with JVM metrics within 30 s").isNotNull();
			try (InputStream body = new GZIPInputStream(new ByteArrayInputStream(push.body()))) {
				request = ExportMetricsServiceRequest.parseFrom(body);
			}
			names = request.getResourceMetricsList().stream()
					.flatMap(r -> r.getScopeMetricsList().stream())
					.flatMap(s -> s.getMetricsList().stream())
					.map(Metric::getName).toList();
		} while (!names.contains("jvm.memory.used"));

		assertThat(push.authorization()).isEqualTo("Basic dGVzdDp0b2tlbg==");
		assertThat(push.encoding()).isEqualTo("gzip");
		assertThat(names).contains("emails.outbox.pending", "seat.holds"); // business metrics travel too
		List<KeyValue> resource = request.getResourceMetrics(0).getResource().getAttributesList();
		assertThat(resource).anySatisfy(attribute -> {
			assertThat(attribute.getKey()).isEqualTo("service.name");
			assertThat(attribute.getValue().getStringValue()).isEqualTo("BookMySeat");
		}).anySatisfy(attribute -> {
			assertThat(attribute.getKey()).isEqualTo("deployment.environment.name");
			assertThat(attribute.getValue().getStringValue()).isEqualTo("local");
		});
	}
}
