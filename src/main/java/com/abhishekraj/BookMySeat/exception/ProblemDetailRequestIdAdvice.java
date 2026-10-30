package com.abhishekraj.BookMySeat.exception;

import com.abhishekraj.BookMySeat.config.RequestIdFilter;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

// Adds "requestId" to every ProblemDetail error body, so a user can quote it and it can be found in the logs
@RestControllerAdvice
public class ProblemDetailRequestIdAdvice implements ResponseBodyAdvice<Object> {

	@Override
	public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
		return true;
	}

	@Override
	public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
			Class<? extends HttpMessageConverter<?>> converterType, ServerHttpRequest request, ServerHttpResponse response) {
		if (body instanceof ProblemDetail problem) {
			problem.setProperty("requestId", RequestIdFilter.current());
		}
		return body;
	}
}
