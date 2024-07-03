package com.github.pdfinvoice.parse;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
public class Invoice {

    @Schema(description = "标题")
    private String title;
    @Schema(description = "机器编码")
    private String machineNumber;
    @Schema(description = "发票代码")
    private String code;
    @Schema(description = "发票号码")
    private String number;
    @Schema(description = "开票日期")
    private String date;
    @Schema(description = "校验码")
    private String checksum;
    @Schema(description = "购方名称")
    private String buyerName;
    @Schema(description = "购方纳税人识别号")
    private String buyerCode;
    @Schema(description = "购方地址")
    private String buyerAddress;
    @Schema(description = "购方开户行及账号")
    private String buyerAccount;
    @Schema(description = "密码区")
    private String password;
    @Schema(description = "金额")
    private BigDecimal amount;
    @Schema(description = "税费")
    private BigDecimal taxAmount;
    @Schema(description = "总金额 (金额+税费)")
    private String totalAmountString;
    @Schema(description = "总金额 (金额+税费)")
    private BigDecimal totalAmount;
    @Schema(description = "售方名称")
    private String sellerName;
    @Schema(description = "售方纳税人识别号")
    private String sellerCode;
    @Schema(description = "售方地址")
    private String sellerAddress;
    @Schema(description = "售方开户行及账号")
    private String sellerAccount;
    @Schema(description = "备注")
    private String remark;
    @Schema(description = "收款人")
    private String payee;
    @Schema(description = "复核")
    private String reviewer;
    @Schema(description = "开票人")
    private String drawer;
    @Schema(description = "类型  普通发票/专用发票")
    private String type;
    @Schema(description = "明细首项名称")
    private String firstRecName;
    @Schema(description = "明细")
    private List<Detail> detailList;

}

@Getter
@Setter
class Detail {
    @Schema(description = "名称")
    private String name;
    @Schema(description = "规格")
    private String model;
    @Schema(description = "单位")
    private String unit;
    @Schema(description = "数量")
    private String count;
    @Schema(description = "单价")
    private String price;
    @Schema(description = "价格")
    private String amount;
    @Schema(description = "税率")
    private String taxRate;
    @Schema(description = "税费")
    private String taxAmount;

}
