package com.qqmu.muopt.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * 启动时打印配置库的实际存储位置，便于排查「换了启动方式 / 工作目录后配置不见了」：
 * H2 文件路径相对于进程工作目录解析，IDEA 运行、java -jar 在不同目录启动会各建一份库，
 * 可用环境变量 APP_DATA_DIR 固定为绝对路径。
 */
@Slf4j
@Component
public class StartupInfoLogger implements ApplicationRunner {

    @Value("${app.data.dir:./data}")
    private String dataDir;

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    @Override
    public void run(ApplicationArguments args) {
        String absDir = new File(dataDir).getAbsolutePath();
        log.info("配置库位置: {}（JDBC: {}）。数据源与 AI 模型配置保存在此，重启不丢；"
                + "如需固定位置请设置环境变量 APP_DATA_DIR", absDir, datasourceUrl);
    }
}
