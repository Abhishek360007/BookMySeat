package com.abhishekraj.BookMySeat;

import com.abhishekraj.BookMySeat.entity.Role;
import com.abhishekraj.BookMySeat.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// BCrypt takes about 1.5 s on the 0.1 CPU instance. A login that checked the password inside its transaction held a
// database connection all that time (seen in a trace: 1.5 s connection, 0.1 s of queries), so 5 logins at once used
// up production's whole pool. The check must run with no transaction, and so no connection, open.
@SpringBootTest(properties = {
		"rate-limit.login.max-requests=1000",
		"rate-limit.register.max-requests=1000"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, LoginConnectionTest.RecordingEncoder.class})
class LoginConnectionTest {

	@TestConfiguration(proxyBeanMethods = false)
	static class RecordingEncoder {
		static final List<Boolean> transactionActiveDuringCheck = new CopyOnWriteArrayList<>();

		@Bean
		@Primary
		PasswordEncoder recordingPasswordEncoder() {
			BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
			return new PasswordEncoder() {
				@Override
				public String encode(CharSequence raw) {
					return bcrypt.encode(raw);
				}

				@Override
				public boolean matches(CharSequence raw, String encoded) {
					transactionActiveDuringCheck.add(TransactionSynchronizationManager.isActualTransactionActive());
					return bcrypt.matches(raw, encoded);
				}
			};
		}
	}

	@Autowired MockMvc mvc;
	@Autowired UserRepository userRepository;

	@AfterEach
	void cleanUp() {
		userRepository.deleteAll(userRepository.findAll().stream().filter(u -> u.getRole() != Role.ADMIN).toList());
		RecordingEncoder.transactionActiveDuringCheck.clear();
	}

	@Test
	void passwordCheck_runsWithoutATransaction_forRightAndWrongPasswords() throws Exception {
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Login Test\",\"email\":\"login-conn@example.com\",\"password\":\"Login-Conn-Pw-123\"}"))
				.andExpect(status().isCreated());

		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"login-conn@example.com\",\"password\":\"Login-Conn-Pw-123\"}"))
				.andExpect(status().isOk());
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"login-conn@example.com\",\"password\":\"Wrong-Password-123\"}"))
				.andExpect(status().isUnauthorized());

		assertThat(RecordingEncoder.transactionActiveDuringCheck).containsExactly(false, false);
	}
}
