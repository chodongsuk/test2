package com.transitcard.reader;

public class Transaction {
    private final String date;
    private final String location;
    private final int amount;
    private final int balanceAfter;
    private final TransactionType transactionType;

    public Transaction(String date, String location, int amount, int balanceAfter, TransactionType transactionType) {
        this.date = date;
        this.location = location;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.transactionType = transactionType;
    }

    public String getDate() {
        return date;
    }

    public String getLocation() {
        return location;
    }

    public int getAmount() {
        return amount;
    }

    public int getBalanceAfter() {
        return balanceAfter;
    }

    public TransactionType getTransactionType() {
        return transactionType;
    }
}
