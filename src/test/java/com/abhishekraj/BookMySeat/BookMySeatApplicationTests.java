package com.abhishekraj.BookMySeat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfig.class)
class BookMySeatApplicationTests {

	@Test
	void contextLoads() {
	}

}
