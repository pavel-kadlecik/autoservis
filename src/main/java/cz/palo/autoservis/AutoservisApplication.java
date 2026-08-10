package cz.palo.autoservis;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan({"cz.palo.autoservis.mapper", "cz.palo.autoservis.security.mapper"})
public class AutoservisApplication {

	public static void main(String[] args) {
		SpringApplication.run(AutoservisApplication.class, args);
	}

}
