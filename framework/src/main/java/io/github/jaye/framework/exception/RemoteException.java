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

/**
 * 远程服务调用异常
 * 比如订单调用支付失败，向上抛出的异常应该是远程服务调用异常
 */
public class RemoteException extends AbstractException {

    /**
     * 按提示信息创建远程调用异常，默认使用 C 类第三方服务错误码
     */
    public RemoteException(String message) {
        this(message, null, BaseErrorCode.REMOTE_ERROR);
    }

    /**
     * 按提示信息和错误码创建远程调用异常
     */
    public RemoteException(String message, IErrorCode errorCode) {
        this(message, null, errorCode);
    }

    /**
     * 全参构造，可携带原始异常堆栈
     */
    public RemoteException(String message, Throwable throwable, IErrorCode errorCode) {
        super(message, throwable, errorCode);
    }

    /**
     * 输出错误码与错误信息的简要描述，便于日志排查
     */
    @Override
    public String toString() {
        return "RemoteException{" +
                "code='" + errorCode + "'," +
                "message='" + errorMessage + "'" +
                '}';
    }
}
