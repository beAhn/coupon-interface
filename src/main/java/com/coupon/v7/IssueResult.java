package com.coupon.v7;

// v7-2 발급 결과 (boolean으로는 품절/중복/오류 구분 불가)
public enum IssueResult {
    SUCCESS,
    SOLD_OUT,
    DUPLICATE,
    ERROR
}
