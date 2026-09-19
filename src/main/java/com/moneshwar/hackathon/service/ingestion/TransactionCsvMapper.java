package com.moneshwar.hackathon.service.ingestion;

import com.moneshwar.hackathon.dto.transaction.TransactionRequest;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

@Component
public class TransactionCsvMapper {

    public TransactionRequest fromCsv(Map<String, String> row) {
        TransactionRequest request = new TransactionRequest();
        request.setTransactionRef(CsvValueParser.str(row, "transaction_ref"));
        request.setAccountId(CsvValueParser.str(row, "account_id"));
        request.setAmount(CsvValueParser.decimal(row, "amount"));
        request.setCurrency(CsvValueParser.currency(row, "currency"));
        request.setTransactionType(CsvValueParser.str(row, "transaction_type"));
        request.setDirection(CsvValueParser.str(row, "direction"));
        request.setCounterpartyName(CsvValueParser.str(row, "counterparty_name"));
        request.setCounterpartyAccount(CsvValueParser.str(row, "counterparty_account"));
        request.setCounterpartyCountry(upper(CsvValueParser.str(row, "counterparty_country")));
        request.setChannel(CsvValueParser.str(row, "channel"));
        request.setJurisdiction(upper(CsvValueParser.str(row, "jurisdiction")));
        request.setDescription(CsvValueParser.str(row, "description"));
        request.setTransactionTime(CsvValueParser.timestamp(row, "transaction_time"));
        return request;
    }

    private String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }
}
