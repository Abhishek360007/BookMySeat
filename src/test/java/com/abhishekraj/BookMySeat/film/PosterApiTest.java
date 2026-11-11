package com.abhishekraj.BookMySeat.film;

import com.abhishekraj.BookMySeat.TestcontainersConfig;
import com.abhishekraj.BookMySeat.entity.Role;
import com.abhishekraj.BookMySeat.repository.FilmRepository;
import com.abhishekraj.BookMySeat.repository.UserRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Posters over HTTP: uploading (admin only), what is refused and why, replacing and removing.
// Same configuration as BookingApiTest (shared Spring context).
@SpringBootTest(properties = {
		"app.admin.email=api-test-admin@example.com",
		"app.admin.password=Api-Test-Admin-Pw-1",
		"rate-limit.login.max-requests=1000",
		"rate-limit.register.max-requests=1000"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class PosterApiTest {

	@Autowired MockMvc mvc;
	@Autowired FilmRepository filmRepository;
	@Autowired FilmPosterRepository posterRepository;
	@Autowired UserRepository userRepository;
	@Autowired JdbcTemplate jdbc;

	String admin;
	long filmId;

	@BeforeEach
	void setUp() throws Exception {
		admin = login("api-test-admin@example.com", "Api-Test-Admin-Pw-1");
		filmId = ((Number) JsonPath.read(mvc.perform(post("/api/admin/films").header("Authorization", "Bearer " + admin)
				.contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Poster Film\",\"runtimeMinutes\":100}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id")).longValue();
	}

	@AfterEach
	void cleanUp() {
		jdbc.update("delete from films f where not exists (select 1 from shows s where s.film_id = f.id)");
		userRepository.deleteAll(userRepository.findAll().stream().filter(u -> u.getRole() != Role.ADMIN).toList());
	}

	@Test
	void anAdminUploads_andGetsA480By720Jpeg() throws Exception {
		String version = JsonPath.read(upload(admin, "poster.png", PosterImagesTest.image("png", 1000, 1500,
				BufferedImage.TYPE_INT_RGB, Color.ORANGE)).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString(), "$.posterVersion");

		FilmPoster stored = posterRepository.findById(filmId).orElseThrow();
		BufferedImage image = ImageIO.read(new ByteArrayInputStream(stored.getContent()));
		assertThat(image.getWidth()).isEqualTo(480);
		assertThat(image.getHeight()).isEqualTo(720);
		assertThat(stored.getVersion()).isEqualTo(version);
		assertThat(filmRepository.findById(filmId).orElseThrow().getPosterVersion()).isEqualTo(version);

		// Replacing it: one row, a new version
		String next = JsonPath.read(upload(admin, "other.jpg", PosterImagesTest.image("jpeg", 800, 1200,
				BufferedImage.TYPE_INT_RGB, Color.BLUE)).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString(), "$.posterVersion");
		assertThat(next).isNotEqualTo(version);
		assertThat(posterRepository.count()).isEqualTo(1);
	}

	@Test
	void whatCantBeAPoster_isRefused_withTheReason() throws Exception {
		upload(admin, "poster.gif", PosterImagesTest.image("gif", 400, 600, BufferedImage.TYPE_INT_RGB, Color.RED))
				.andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.reason").value("UNSUPPORTED_TYPE"));
		upload(admin, "tiny.png", PosterImagesTest.image("png", 100, 150, BufferedImage.TYPE_INT_RGB, Color.RED))
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.reason").value("TOO_SMALL"));
		// Renamed to .png, but the bytes say otherwise
		upload(admin, "fake.png", "not an image at all".getBytes())
				.andExpect(status().isUnsupportedMediaType());
		byte[] huge = new byte[(int) FilmPosterService.MAX_UPLOAD_BYTES + 1];
		huge[0] = (byte) 0xFF;
		huge[1] = (byte) 0xD8;
		huge[2] = (byte) 0xFF;
		upload(admin, "huge.jpg", huge).andExpect(status().isPayloadTooLarge())
				.andExpect(jsonPath("$.reason").value("FILE_TOO_BIG"));
		assertThat(posterRepository.count()).isZero();
	}

	@Test
	void onlyAdmins_andOnlyForAFilm() throws Exception {
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Pia\",\"email\":\"poster-user@example.com\",\"password\":\"Poster-Pw-1234\"}"));
		String user = login("poster-user@example.com", "Poster-Pw-1234");
		byte[] png = PosterImagesTest.image("png", 400, 600, BufferedImage.TYPE_INT_RGB, Color.RED);
		upload(user, "p.png", png).andExpect(status().isForbidden());
		mvc.perform(multipart("/api/admin/films/" + filmId + "/poster").file(new MockMultipartFile("file", "p.png", "image/png", png))
				.with(r -> { r.setMethod("PUT"); return r; })).andExpect(status().isUnauthorized());
		mvc.perform(multipart("/api/admin/films/999999/poster").file(new MockMultipartFile("file", "p.png", "image/png", png))
				.with(r -> { r.setMethod("PUT"); return r; }).header("Authorization", "Bearer " + admin))
				.andExpect(status().isNotFound());
	}

	@Test
	void removingAPoster_twice_isFine() throws Exception {
		upload(admin, "p.png", PosterImagesTest.image("png", 400, 600, BufferedImage.TYPE_INT_RGB, Color.RED))
				.andExpect(status().isOk());
		for (int i = 0; i < 2; i++) {
			mvc.perform(delete("/api/admin/films/" + filmId + "/poster").header("Authorization", "Bearer " + admin))
					.andExpect(status().isNoContent());
		}
		assertThat(posterRepository.count()).isZero();
		assertThat(filmRepository.findById(filmId).orElseThrow().getPosterVersion()).isNull();
	}

	ResultActions upload(String token, String name, byte[] content) throws Exception {
		return mvc.perform(multipart("/api/admin/films/" + filmId + "/poster")
				.file(new MockMultipartFile("file", name, "application/octet-stream", content))
				.with(r -> { r.setMethod("PUT"); return r; })
				.header("Authorization", "Bearer " + token));
	}

	String login(String email, String password) throws Exception {
		return JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");
	}
}
