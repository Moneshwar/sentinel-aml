package com.moneshwar.hackathon.service.ingestion;

import com.moneshwar.hackathon.dto.account.AccountRequest;
import com.moneshwar.hackathon.entity.enums.AccountStatus;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class AccountCsvMapper {

    public AccountRequest fromCsv(Map<String, String> row) {
        AccountRequest request = new AccountRequest();
        request.setAccountId(CsvValueParser.str(row, "account_id"));
        request.setCustomerId(CsvValueParser.str(row, "customer_id"));
        request.setRiskRating(CsvValueParser.enumValue(row, "risk_rating", com.moneshwar.hackathon.entity.enums.RiskRating.class, null));
        request.setAccountType(CsvValueParser.str(row, "account_type"));
        request.setAccountStatus(CsvValueParser.enumValue(row, "account_status", AccountStatus.class, null));
        request.setCurrency(CsvValueParser.currency(row, "currency"));
        request.setOpenDate(CsvValueParser.date(row, "open_date"));
        request.setCloseDate(CsvValueParser.date(row, "close_date"));
        request.setBranchCode(CsvValueParser.str(row, "branch_code"));
        request.setBranchCity(CsvValueParser.str(row, "branch_city"));
        request.setCurrentBalance(CsvValueParser.decimal(row, "current_balance"));
        request.setAvgMonthlyBalance6m(CsvValueParser.decimal(row, "avg_monthly_balance_6m"));
        request.setCreditLimit(CsvValueParser.decimal(row, "credit_limit"));
        request.setCreditUtilizationPct(CsvValueParser.decimal(row, "credit_utilization_pct"));
        request.setOverdraftEnabled(CsvValueParser.bool(row, "overdraft_enabled"));
        request.setCardType(CsvValueParser.str(row, "card_type"));
        request.setJointAccount(CsvValueParser.bool(row, "is_joint_account"));
        request.setNumLinkedDevices(CsvValueParser.integer(row, "num_linked_devices"));
        request.setMobileBankingEnrolled(CsvValueParser.bool(row, "mobile_banking_enrolled"));
        request.setLastLoginDate(CsvValueParser.date(row, "last_login_date"));
        request.setAvgMonthlyTxnCount(CsvValueParser.integer(row, "avg_monthly_txn_count"));
        request.setAccountTier(CsvValueParser.str(row, "account_tier"));
        return request;
    }
}
