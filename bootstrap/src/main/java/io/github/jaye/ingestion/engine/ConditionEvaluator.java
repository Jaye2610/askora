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

package io.github.jaye.ai.askora.ingestion.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jaye.ai.askora.ingestion.domain.context.IngestionContext;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;
import java.util.function.IntPredicate;

/**
 * 条件评估器
 * 用于根据给定的 IngestionContext 上下文和 JsonNode 格式的条件配置来评估条件是否满足
 */
@Component
public class ConditionEvaluator {

    private final ObjectMapper objectMapper;
    private final ExpressionParser parser = new SpelExpressionParser();

    public ConditionEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 评估条件是否满足：先做结构校验，非法条件一律视为不满足
     */
    public boolean evaluate(IngestionContext context, JsonNode condition) {
        return isStructurallyValid(condition) && evaluateValid(context, condition);
    }

    /**
     * 校验条件结构是否合法
     * <p>
     * 支持 boolean、SpEL 字符串、all/any/not 组合以及 field-operator-value 规则四种形态，
     * 非法结构直接判定为不满足，避免把格式错误的配置当成 true 放行
     */
    private boolean isStructurallyValid(JsonNode condition) {
        if (condition == null || condition.isNull() || condition.isBoolean() || condition.isTextual()) {
            return true;
        }
        if (!condition.isObject()) {
            return false;
        }
        if (condition.has("all")) {
            return isStructurallyValidGroup(condition.get("all"));
        }
        if (condition.has("any")) {
            return isStructurallyValidGroup(condition.get("any"));
        }
        if (condition.has("not")) {
            return isStructurallyValid(condition.get("not"));
        }
        if (condition.has("field")) {
            return StringUtils.hasText(condition.path("field").asText(null));
        }
        return false;
    }

    /**
     * 校验 all/any 组合的条件数组里每个子条件都合法
     */
    private boolean isStructurallyValidGroup(JsonNode node) {
        if (node == null || !node.isArray()) {
            return false;
        }
        for (JsonNode item : node) {
            if (!isStructurallyValid(item)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 对结构合法的条件求值：null 视为无条件（放行），按条件形态分发到对应求值逻辑
     */
    private boolean evaluateValid(IngestionContext context, JsonNode condition) {
        if (condition == null || condition.isNull()) {
            return true;
        }
        if (condition.isBoolean()) {
            return condition.asBoolean();
        }
        if (condition.isTextual()) {
            return evalSpel(context, condition.asText());
        }
        if (condition.isObject()) {
            if (condition.has("all")) {
                return evalAll(context, condition.get("all"));
            }
            if (condition.has("any")) {
                return evalAny(context, condition.get("any"));
            }
            if (condition.has("not")) {
                return !evaluateValid(context, condition.get("not"));
            }
            if (condition.has("field")) {
                return evalRule(context, condition);
            }
        }
        return false;
    }

    /**
     * all 组合求值：全部子条件满足才为真（短路失败）
     */
    private boolean evalAll(IngestionContext context, JsonNode node) {
        if (node == null || !node.isArray()) {
            return false;
        }
        for (JsonNode item : node) {
            if (!evaluateValid(context, item)) {
                return false;
            }
        }
        return true;
    }

    /**
     * any 组合求值：任一子条件满足即为真（短路成功）
     */
    private boolean evalAny(IngestionContext context, JsonNode node) {
        if (node == null || !node.isArray()) {
            return false;
        }
        for (JsonNode item : node) {
            if (evaluateValid(context, item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 单条 field-operator-value 规则求值：从上下文读字段值，与配置值按运算符比较
     */
    private boolean evalRule(IngestionContext context, JsonNode node) {
        String field = node.path("field").asText(null);
        if (!StringUtils.hasText(field)) {
            return false;
        }
        String operator = node.path("operator").asText("eq");
        JsonNode valueNode = node.get("value");
        Object left = readField(context, field);
        Object right = valueNode == null ? null : objectMapper.convertValue(valueNode, Object.class);
        return compare(left, right, operator);
    }

    /**
     * 按属性路径从上下文读字段值，读不到（路径不存在等）返回 null 而非抛异常
     */
    private Object readField(IngestionContext context, String path) {
        try {
            BeanWrapperImpl wrapper = new BeanWrapperImpl(context);
            return wrapper.getPropertyValue(path);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 按运算符分发比较逻辑：eq/ne/in/contains/regex/gt/gte/lt/lte/exists/not_exists，未知运算符退化为相等比较
     */
    private boolean compare(Object left, Object right, String operator) {
        return switch (operator.toLowerCase()) {
            case "ne" -> !Objects.equals(normalize(left), normalize(right));
            case "in" -> in(left, right);
            case "contains" -> contains(left, right);
            case "regex" -> regex(left, right);
            case "gt" -> compareNumbers(left, right, result -> result > 0);
            case "gte" -> compareNumbers(left, right, result -> result >= 0);
            case "lt" -> compareNumbers(left, right, result -> result < 0);
            case "lte" -> compareNumbers(left, right, result -> result <= 0);
            case "exists" -> left != null;
            case "not_exists" -> left == null;
            default -> Objects.equals(normalize(left), normalize(right));
        };
    }

    /**
     * in 运算：值在集合中，或集合包含值，两边都不是集合时退化为相等比较
     */
    private boolean in(Object left, Object right) {
        if (right instanceof List<?> list) {
            return list.contains(left);
        }
        if (left instanceof List<?> list) {
            return list.contains(right);
        }
        return Objects.equals(normalize(left), normalize(right));
    }

    /**
     * contains 运算：字符串包含子串，或列表包含元素
     */
    private boolean contains(Object left, Object right) {
        if (left == null || right == null) {
            return false;
        }
        if (left instanceof String ls) {
            return ls.contains(String.valueOf(right));
        }
        if (left instanceof List<?> list) {
            return list.contains(right);
        }
        return false;
    }

    /**
     * regex 运算：字段值匹配给定正则
     */
    private boolean regex(Object left, Object right) {
        if (left == null || right == null) {
            return false;
        }
        return String.valueOf(left).matches(String.valueOf(right));
    }

    /**
     * 数值比较：两边转成 Double 后按给定谓词判定，任一边转不了数值即为不满足
     */
    private boolean compareNumbers(Object left, Object right, IntPredicate predicate) {
        Double l = toDouble(left);
        Double r = toDouble(right);
        if (l == null || r == null) {
            return false;
        }
        return predicate.test(Double.compare(l, r));
    }

    /**
     * 把任意值转成 Double，Number 直接取值，其他尝试解析字符串，NaN/Infinity 一律视为不可比较
     */
    private Double toDouble(Object value) {
        Double number;
        if (value instanceof Number n) {
            number = n.doubleValue();
        } else {
            try {
                number = Double.parseDouble(String.valueOf(value));
            } catch (Exception e) {
                return null;
            }
        }
        return Double.isFinite(number) ? number : null;
    }

    /**
     * 字符串去首尾空白，规避配置里带空格导致的误判
     */
    private Object normalize(Object value) {
        if (value instanceof String s) {
            return s.trim();
        }
        return value;
    }

    /**
     * 求值 SpEL 表达式：以上下文为根对象，可用 #ctx 引用上下文，求值异常视为不满足
     */
    private boolean evalSpel(IngestionContext context, String expression) {
        try {
            StandardEvaluationContext ctx = new StandardEvaluationContext(context);
            ctx.setVariable("ctx", context);
            Boolean result = parser.parseExpression(expression).getValue(ctx, Boolean.class);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            return false;
        }
    }
}
