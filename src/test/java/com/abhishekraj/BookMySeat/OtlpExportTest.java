package com.abhishekraj.BookMySeat;

import com.google.protobuf.ByteString;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.trace.v1.Span;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

// Metrics and traces leave the app the way Grafana Cloud expects them: OTLP over HTTP, gzipped, with the auth
// header. A tiny HTTP server stands in for the OTLP gateway.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"management.otlp.metrics.export.enabled=true",
		"management.otlp.metrics.export.step=PT1S",
		"management.otlp.metrics.export.headers.Authorization=Basic dGVzdDp0b2tlbg==",
		"management.tracing.export.otlp.enabled=true",
		"management.opentelemetry.tracing.export.schedule-delay=PT0.2S",
		"management.opentelemetry.tracing.export.otlp.headers.Authorization=Basic dGVzdDp0b2tlbg==",
		// The jobs poll the database every 2 s here, so their (unwanted) spans would show up in the tests
		"email.outbox.initial-delay=PT1S", "email.outbox.interval=PT2S",
		"payments.maintenance.initial-delay=PT1S", "payments.maintenance.interval=PT2S"
})
@AutoConfigureMetrics // tests normally switch exporters off
@AutoConfigureTracing
@Import(TestcontainersConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class OtlpExportTest {

	record Received(String authorization, String encoding, byte[] body) {

		InputStream unzipped() throws IOException {
			return new GZIPInputStream(new ByteArrayInputStream(body));
		}
	}

	static final BlockingQueue<Received> metricPushes = new LinkedBlockingQueue<>();
	static final BlockingQueue<Received> tracePushes = new LinkedBlockingQueue<>();
	static final HttpServer gateway = startGateway();

	static HttpServer startGateway() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			server.createContext("/otlp/v1/metrics", exchange -> record(exchange, metricPushes));
			server.createContext("/otlp/v1/traces", exchange -> record(exchange, tracePushes));
			server.start();
			return server;
		} catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	static void record(com.sun.net.httpserver.HttpExchange exchange, BlockingQueue<Received> into) throws IOException {
		try (InputStream body = exchange.getRequestBody()) {
			into.add(new Received(exchange.getRequestHeaders().getFirst("Authorization"),
					exchange.getRequestHeaders().getFirst("Content-Encoding"), body.readAllBytes()));
		}
		exchange.sendResponseHeaders(200, -1);
		exchange.close();
	}

	@DynamicPropertySource
	static void gatewayUrl(DynamicPropertyRegistry registry) {
		String base = "http://localhost:" + gateway.getAddress().getPort() + "/otlp";
		registry.add("management.otlp.metrics.export.url", () -> base + "/v1/metrics");
		registry.add("management.opentelemetry.tracing.export.otlp.endpoint", () -> base + "/v1/traces");
	}

	@AfterAll
	static void stopGateway() {
		gateway.stop(0);
	}

	@LocalServerPort int port;

	@Test
	void metrics_pushGzippedOtlpWithTheAuthHeader_includingBusinessMetrics() throws Exception {
		// The first pushes can come before the JVM metrics are registered: wait for one that has them
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		Received push;
		ExportMetricsServiceRequest request;
		List<String> names;
		do {
			push = metricPushes.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
			assertThat(push).as("no OTLP push with JVM metrics within 30 s").isNotNull();
			try (InputStream body = push.unzipped()) {
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
		assertResource(request.getResourceMetrics(0).getResource().getAttributesList());
	}

	@Test
	void traces_carryTheRequestId_skipHealthProbes_andTheTraceIdIsInTheLogLine(CapturedOutput output) throws Exception {
		HttpClient http = HttpClient.newHttpClient();
		http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health")).build(),
				HttpResponse.BodyHandlers.discarding());
		HttpResponse<Void> shows = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/shows")).build(),
				HttpResponse.BodyHandlers.discarding());
		String requestId = shows.headers().firstValue("X-Request-Id").orElseThrow();

		Span server = awaitServerSpan(requestId);

		assertThat(server.getKind()).isEqualTo(Span.SpanKind.SPAN_KIND_SERVER);
		assertThat(attribute(server, "uri")).isEqualTo("/api/shows");
		// The probe was sent first, so its span would have been exported by now
		assertThat(spans).noneSatisfy(span -> assertThat(span.getAttributesList())
				.anySatisfy(a -> assertThat(a.getValue().getStringValue()).contains("/actuator")));
		String traceId = hex(server.getTraceId());
		assertThat(output.getAll()).containsPattern("\\[" + requestId + "] \\[trace " + traceId + "] .*GET /api/shows -> 200");
	}

	@Test
	void databaseQueries_areSpansInTheRequestsTrace_withoutParameterValues() throws Exception {
		String email = "claude-trace-" + System.nanoTime() + "@example.com";
		HttpResponse<Void> login = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
						.header("Content-Type", "application/json")
						.POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"" + email + "\",\"password\":\"Not-The-Password-1\"}")).build(),
				HttpResponse.BodyHandlers.discarding());
		assertThat(login.statusCode()).isEqualTo(401); // the user lookup still ran
		Span server = awaitServerSpan(login.headers().firstValue("X-Request-Id").orElseThrow());

		List<Span> trace = spans.stream().filter(span -> span.getTraceId().equals(server.getTraceId())).toList();
		assertThat(trace).anySatisfy(span -> {
			assertThat(span.getName()).isEqualTo("connection"); // taken from the Hikari pool
			assertThat(span.getKind()).isEqualTo(Span.SpanKind.SPAN_KIND_CLIENT);
		}).anySatisfy(span -> {
			assertThat(span.getName()).isEqualTo("query");
			assertThat(span.getKind()).isEqualTo(Span.SpanKind.SPAN_KIND_CLIENT);
			assertThat(attribute(span, "jdbc.query[0]")).contains("from users").endsWith("email=?");
		});
		// The address was a query parameter: it must not be in any span
		assertThat(trace).noneSatisfy(span -> assertThat(span.getAttributesList())
				.anySatisfy(a -> assertThat(a.getValue().getStringValue()).contains(email)));
	}

	@Test
	void idleJobRuns_andQueriesOutsideARequest_makeNoTraces() throws Exception {
		// A request after a few job runs: once its span is exported, so are any spans the jobs made before it
		Thread.sleep(6000);
		HttpResponse<Void> shows = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/shows")).build(), HttpResponse.BodyHandlers.discarding());
		awaitServerSpan(shows.headers().firstValue("X-Request-Id").orElseThrow());

		assertThat(spans).noneSatisfy(span -> assertThat(span.getName()).startsWith("tasks"));
		// A query span with no parent would be a one-span trace of its own (startup, polling jobs)
		assertThat(spans).filteredOn(span -> span.getName().equals("query") || span.getName().equals("connection"))
				.allSatisfy(span -> assertThat(span.getParentSpanId()).as("parent of " + span.getName()).isNotEmpty());
	}

	// Every span exported so far (trace pushes are shared by the tests)
	static final List<Span> spans = new ArrayList<>();

	static Span awaitServerSpan(String requestId) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
		while (true) {
			Optional<Span> server = spans.stream().filter(span -> requestId.equals(attribute(span, "request.id"))).findFirst();
			if (server.isPresent()) {
				return server.get();
			}
			Received push = tracePushes.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
			assertThat(push).as("no span for request " + requestId + " within 20 s").isNotNull();
			assertThat(push.authorization()).isEqualTo("Basic dGVzdDp0b2tlbg==");
			assertThat(push.encoding()).isEqualTo("gzip");
			ExportTraceServiceRequest request;
			try (InputStream body = push.unzipped()) {
				request = ExportTraceServiceRequest.parseFrom(body);
			}
			assertResource(request.getResourceSpans(0).getResource().getAttributesList());
			request.getResourceSpansList().forEach(r -> r.getScopeSpansList().forEach(s -> spans.addAll(s.getSpansList())));
		}
	}

	static void assertResource(List<KeyValue> resource) {
		assertThat(resource).anySatisfy(attribute -> {
			assertThat(attribute.getKey()).isEqualTo("service.name");
			assertThat(attribute.getValue().getStringValue()).isEqualTo("BookMySeat");
		}).anySatisfy(attribute -> {
			assertThat(attribute.getKey()).isEqualTo("deployment.environment.name");
			assertThat(attribute.getValue().getStringValue()).isEqualTo("local");
		});
	}

	static String attribute(Span span, String key) {
		return span.getAttributesList().stream().filter(a -> a.getKey().equals(key))
				.map(a -> a.getValue().getStringValue()).findFirst().orElse(null);
	}

	static String hex(ByteString id) {
		return HexFormat.of().formatHex(id.toByteArray());
	}
}
