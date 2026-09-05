package tech.bhrigu.almira

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class AlmiraApplication

fun main(args: Array<String>) {
    runApplication<AlmiraApplication>(*args)
}
