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

package io.github.jaye.ai.askora.framework.exception;


import io.github.jaye.ai.askora.framework.errorcode.BaseErrorCode;
import io.github.jaye.ai.askora.framework.errorcode.IErrorCode;

import java.util.Optional;

/**
 * 服务端运行异常
 * 请求运行过程中出现的不符合业务预期的异常
 */
public class ServiceException extends AbstractException {

    /**
     * 按提示信息创建服务端异常，默认使用 B 类系统执行错误码
     */
    public ServiceException(String message) {
        this(message, null, BaseErrorCode.SERVICE_ERROR);
    }

    /**
     * 按错误码创建服务端异常
     */
    public ServiceException(IErrorCode errorCode) {
        this(null, errorCode);
    }

    /**
     * 按提示信息和错误码创建服务端异常
     */
    public ServiceException(String message, IErrorCode errorCode) {
        this(message, null, errorCode);
    }

    /**
     * 全参构造，message 为空时兜底错误码默认描述，可携带原始异常堆栈
     */
    public ServiceException(String message, Throwable throwable, IErrorCode errorCode) {
        super(Optional.ofNullable(message).orElse(errorCode.message()), throwable, errorCode);
    }

    /**
     * 输出错误码与错误信息的简要描述，便于日志排查
     */
    @Override
    public String toString() {
        return "ServiceException{" +
                "code='" + errorCode + "'," +
                "message='" + errorMessage + "'" +
                '}';
    }
}
