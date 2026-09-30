/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.jaye.ai.askora;

import com.mzt.logapi.starter.annotation.EnableLogRecord;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Askora 核心应用启动类
 */
@SpringBootApplication
@EnableScheduling
@EnableLogRecord(tenant = "askora", proxyTargetClass = true)
@MapperScan(basePackages = {
        "io.github.jaye.ai.askora.rag.dao.mapper",
        "io.github.jaye.ai.askora.ingestion.dao.mapper",
        "io.github.jaye.ai.askora.knowledge.dao.mapper",
        "io.github.jaye.ai.askora.user.dao.mapper",
        "io.github.jaye.ai.askora.audit.dao.mapper"
})
public class AskoraApplication {

    public static void main(String[] args) {
        SpringApplication.run(AskoraApplication.class, args);
    }
}
