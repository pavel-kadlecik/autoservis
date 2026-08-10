package cz.palo.autoservis;

import org.junit.jupiter.api.Test;

// Extends AbstractIntegrationTest → the context boots against a Testcontainers
// PostgreSQL, not the local dev DB. The whole suite needs only Docker.
class AutoservisApplicationTests extends AbstractIntegrationTest {

	@Test
	void contextLoads() {
	}

}
