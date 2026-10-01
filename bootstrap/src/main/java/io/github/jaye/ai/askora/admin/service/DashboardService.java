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

package io.github.jaye.ai.askora.admin.service;

import io.github.jaye.ai.askora.admin.controller.vo.DashboardOverviewVO;
import io.github.jaye.ai.askora.admin.controller.vo.DashboardPerformanceVO;
import io.github.jaye.ai.askora.admin.controller.vo.DashboardTrendsVO;

/**
 * Dashboard 统计服务接口
 */
public interface DashboardService {

    /**
     * 加载总览 KPI 统计
     */
    DashboardOverviewVO loadOverview(String window);

    /**
     * 加载性能指标统计
     */
    DashboardPerformanceVO loadPerformance(String window);

    /**
     * 加载趋势统计序列
     */
    DashboardTrendsVO loadTrends(String metric, String window, String granularity);
}
