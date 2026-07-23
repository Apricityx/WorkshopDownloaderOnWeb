package top.apricityx.workshopvault

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(WorkshopProperties::class)
class WorkshopVaultApplication

fun main(args: Array<String>) {
    runApplication<WorkshopVaultApplication>(*args)
}
