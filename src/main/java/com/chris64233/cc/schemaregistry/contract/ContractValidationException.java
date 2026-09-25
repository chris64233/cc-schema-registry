package com.chris64233.cc.schemaregistry.contract;

/**
 * 契约文档不合法（重复属性、未知类型、不一致声明等）时抛出，消息稳定且面向调用方。
 */
public class ContractValidationException extends RuntimeException {

    public ContractValidationException(String message) {
        super(message);
    }
}
