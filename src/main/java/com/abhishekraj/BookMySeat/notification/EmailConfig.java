package com.abhishekraj.BookMySeat.notification;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(EmailProperties.class)
public class EmailConfig {

	@Bean
	public EmailSender emailSender(EmailProperties properties, ObjectProvider<JavaMailSender> mailSender) {
		return switch (properties.provider()) {
			case LOG -> new LoggingEmailSender();
			case SMTP -> new SmtpEmailSender(mailSender.getObject(), properties);
			case BREVO -> {
				if (properties.brevo().apiKey() == null || properties.brevo().apiKey().isBlank()) {
					throw new IllegalStateException("email.provider=brevo needs BREVO_API_KEY");
				}
				SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
				requestFactory.setConnectTimeout(Duration.ofSeconds(5));
				requestFactory.setReadTimeout(Duration.ofSeconds(15));
				yield new BrevoEmailSender(RestClient.builder().requestFactory(requestFactory), properties);
			}
		};
	}
}
