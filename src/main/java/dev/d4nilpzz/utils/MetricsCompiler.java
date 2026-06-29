package dev.d4nilpzz.utils;

import io.javalin.websocket.WsContext;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.List;

import com.sun.management.OperatingSystemMXBean;

public class MetricsCompiler {

    public static String getMetricsJson(List<WsContext> clients) {
        OperatingSystemMXBean os = (OperatingSystemMXBean)
                ManagementFactory.getOperatingSystemMXBean();

        int cpu = (int) (os.getCpuLoad() * 100);

        double ramTotal = os.getTotalMemorySize() / (1024.0 * 1024 * 1024);
        double ramFree = os.getFreeMemorySize() / (1024.0 * 1024 * 1024);
        double ramUsed = ramTotal - ramFree;

        File disk = new File("/");
        double diskTotal = disk.getTotalSpace() / (1024.0 * 1024 * 1024);
        double diskFree = disk.getFreeSpace() / (1024.0 * 1024 * 1024);
        double diskUsed = diskTotal - diskFree;

        return """
        {
          "cpu": { "used": %d, "max": 100 },
          "ram": { "used": %.2f, "max": %.2f },
          "storage": { "used": %.2f, "max": %.2f }
        }
        """.formatted(
                cpu,
                ramUsed, ramTotal,
                diskUsed, diskTotal
        );
    }
}