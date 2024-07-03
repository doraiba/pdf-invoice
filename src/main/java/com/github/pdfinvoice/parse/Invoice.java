package com.example.demo.util;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
public class Invoice {
    /**
     * 标题
     */
    private String title;
    /**
     * 机器编码
     */
    private String machineNumber;
    /**
     *发票代码
     */
    private String code;
    /**
     * 发票号码
     */
    private String number;
    /**
     * 开票日期
     */
    private String date;
    /**
     * 校验码
     */
    private String checksum;
    /**
     * 购方名称
     */
    private String buyerName;
    /**
     * 购方纳税人识别号
     */
    private String buyerCode;
    /**
     * 购方地址
     */
    private String buyerAddress;
    /**
     * 购方开户行及账号
     */
    private String buyerAccount;
    /**
     * 密码区
     */
    private String password;
    /**
     * 金额
     */
    private BigDecimal amount;
    /**
     * 税费
     */
    private BigDecimal taxAmount;
    /**
     * 总金额 (金额+税费)
     */
    private String totalAmountString;
    /**
     * 总金额 (金额+税费)
     */
    private BigDecimal totalAmount;
    /**
     * 售方名称
     */
    private String sellerName;
    /**
     * 售方纳税人识别号
     */
    private String sellerCode;
    /**
     * 售方地址
     */
    private String sellerAddress;
    /**
     * 售方开户行及账号
     */
    private String sellerAccount;
    /**
     * 备注
     */
    private String remark;

    /**
     * 收款人
     */
    private String payee;
    /**
     * 复核
     */
    private String reviewer;
    /**
     * 开票人
     */
    private String drawer;
    /**
     * 类型  普通发票/专用发票
     */
    private String type;

    private String firstRecName;

    /**
     * 明细
     */
    private List<Detail> detailList;

}

@Getter
@Setter
class Detail {
    private String name;
    private String model;
    private String unit;
    private String count;
    private String price;
    private String amount;
    private String taxRate;
    private String taxAmount;

}
