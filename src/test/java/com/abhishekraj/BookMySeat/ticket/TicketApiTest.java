package com.abhishekraj.BookMySeat.ticket;

import com.abhishekraj.BookMySeat.TestcontainersConfig;
import com.abhishekraj.BookMySeat.entity.Role;
import com.abhishekraj.BookMySeat.repository.BookingRepository;
import com.abhishekraj.BookMySeat.repository.SeatRepository;
import com.abhishekraj.BookMySeat.repository.ShowRepository;
import com.abhishekraj.BookMySeat.repository.UserRepository;
import com.abhishekraj.BookMySeat.repository.VenueRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Same configuration as BookingApiTest (shared Spring context)
@SpringBootTest(properties = {
		"app.admin.email=api-test-admin@example.com",
		"app.admin.password=Api-Test-Admin-Pw-1",
		"rate-limit.login.max-requests=1000",
		"rate-limit.register.max-requests=1000"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class TicketApiTest {

	@Autowired MockMvc mvc;
	@Autowired TicketCodes codes;
	@Autowired BookingRepository bookingRepository;
	@Autowired SeatRepository seatRepository;
	@Autowired ShowRepository showRepository;
	@Autowired VenueRepository venueRepository;
	@Autowired UserRepository userRepository;
	@Autowired StringRedisTemplate redis;

	String admin;
	String user;
	long showId;
	Instant showStart;
	int bookingId;

	@BeforeEach
	void setUp() throws Exception {
		admin = login("api-test-admin@example.com", "Api-Test-Admin-Pw-1");
		showId = createShowWithSeats();
		user = registerAndLogin("ticket-holder@example.com", "Tara");
		List<Integer> seats = JsonPath.read(mvc.perform(get("/api/shows/" + showId + "/seats"))
				.andReturn().getResponse().getContentAsString(), "$[*].id");
		String body = "{\"showId\":" + showId + ",\"seatIds\":" + List.of(seats.get(1), seats.get(0)) + "}";
		mvc.perform(post("/api/bookings/hold").header("Authorization", "Bearer " + user)
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
		bookingId = JsonPath.read(mvc.perform(post("/api/bookings").header("Authorization", "Bearer " + user)
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
	}

	@AfterEach
	void cleanUp() {
		bookingRepository.deleteAll();
		seatRepository.deleteAll();
		showRepository.deleteAll();
		venueRepository.deleteAll();
		userRepository.deleteAll(userRepository.findAll().stream().filter(u -> u.getRole() != Role.ADMIN).toList());
		redis.delete(redis.keys("hold:*"));
	}

	@Test
	void ownerGetsASignedTicket_andAQrThatDecodesToTheSameCode() throws Exception {
		String json = mvc.perform(get("/api/bookings/" + bookingId + "/ticket").header("Authorization", "Bearer " + user))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.bookingId").value(bookingId))
				.andExpect(jsonPath("$.showTitle").value("Ticket Show"))
				.andExpect(jsonPath("$.seats[0]").value("A1"))
				.andExpect(jsonPath("$.seats[1]").value("A2"))
				.andExpect(jsonPath("$.holderName").value("Tara"))
				.andReturn().getResponse().getContentAsString();
		String code = JsonPath.read(json, "$.code");
		assertThat(codes.bookingIdOf(code)).contains((long) bookingId);

		byte[] png = mvc.perform(get("/api/bookings/" + bookingId + "/ticket.png").header("Authorization", "Bearer " + user))
				.andExpect(status().isOk())
				.andExpect(content().contentType(MediaType.IMAGE_PNG))
				.andReturn().getResponse().getContentAsByteArray();
		assertThat(QrDecoder.decode(png)).isEqualTo(code);
	}

	@Test
	void ownerGetsTheBookingAsACalendarEvent() throws Exception {
		var response = mvc.perform(get("/api/bookings/" + bookingId + "/ticket.ics").header("Authorization", "Bearer " + user))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith("text/calendar"))
				.andReturn().getResponse();
		assertThat(response.getHeader("Content-Disposition"))
				.isEqualTo("attachment; filename=\"booking-" + bookingId + ".ics\"");
		String ics = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8).replace("\r\n ", "");
		DateTimeFormatter utc = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
		assertThat(ics).startsWith("BEGIN:VCALENDAR\r\n").endsWith("END:VCALENDAR\r\n")
				.contains("UID:booking-" + bookingId + "@bookmyseat\r\n",
						"DTSTART:" + utc.format(showStart) + "\r\n",
						"DTEND:" + utc.format(showStart.plus(3, ChronoUnit.HOURS)) + "\r\n",
						"SUMMARY:Ticket Show\r\n",
						"LOCATION:Ticket Venue\\, Pune\r\n", // commas are escaped in iCalendar text
						"DESCRIPTION:Booking #" + bookingId + "\\, seats A1\\, A2\\n");
	}

	@Test
	void someoneElsesBooking_is404_andNoTokenIs401() throws Exception {
		String other = registerAndLogin("someone-else@example.com", "Sam");
		mvc.perform(get("/api/bookings/" + bookingId + "/ticket").header("Authorization", "Bearer " + other))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/bookings/" + bookingId + "/ticket.png").header("Authorization", "Bearer " + other))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/bookings/" + bookingId + "/ticket.ics").header("Authorization", "Bearer " + other))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/bookings/" + bookingId + "/ticket")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/bookings/" + bookingId + "/ticket.ics")).andExpect(status().isUnauthorized());
	}

	@Test
	void cancelledBooking_hasNoTicket() throws Exception {
		mvc.perform(post("/api/bookings/" + bookingId + "/cancel").header("Authorization", "Bearer " + user))
				.andExpect(status().isOk());
		mvc.perform(get("/api/bookings/" + bookingId + "/ticket").header("Authorization", "Bearer " + user))
				.andExpect(status().isConflict());
		mvc.perform(get("/api/bookings/" + bookingId + "/ticket.ics").header("Authorization", "Bearer " + user))
				.andExpect(status().isConflict());
	}

	long createShowWithSeats() throws Exception {
		int venueId = JsonPath.read(mvc.perform(post("/api/venues").header("Authorization", "Bearer " + admin)
						.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Ticket Venue\",\"city\":\"Pune\"}"))
				.andReturn().getResponse().getContentAsString(), "$.id");
		Instant start = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
		showStart = start;
		int id = JsonPath.read(mvc.perform(post("/api/shows").header("Authorization", "Bearer " + admin)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"title\":\"Ticket Show\",\"startTime\":\"" + start + "\",\"price\":250.00,\"venueId\":" + venueId + "}"))
				.andReturn().getResponse().getContentAsString(), "$.id");
		mvc.perform(post("/api/shows/" + id + "/seats").header("Authorization", "Bearer " + admin)
				.contentType(MediaType.APPLICATION_JSON).content("{\"rows\":1,\"seatsPerRow\":4}"));
		return id;
	}

	String registerAndLogin(String email, String name) throws Exception {
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"" + name + "\",\"email\":\"" + email + "\",\"password\":\"Ticket-Pw-12345\"}"));
		return login(email, "Ticket-Pw-12345");
	}

	String login(String email, String password) throws Exception {
		return JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");
	}
}
