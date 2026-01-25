package com.transitcard.reader;

import java.util.ArrayList;
import java.util.List;

public class TransitCardData {
    private final CardType cardType;
    private final String cardNumber;
    private final int balance;
    private final List<Transaction> transactionHistory;

    public TransitCardData(CardType cardType, String cardNumber, int balance, List<Transaction> transactionHistory) {
        this.cardType = cardType;
        this.cardNumber = cardNumber;
        this.balance = balance;
        this.transactionHistory = transactionHistory != null ? transactionHistory : new ArrayList<>();
    }

    public TransitCardData(CardType cardType, String cardNumber, int balance) {
        this(cardType, cardNumber, balance, new ArrayList<>());
    }

    public CardType getCardType() {
        return cardType;
    }

    public String getCardNumber() {
        return cardNumber;
    }

    public int getBalance() {
        return balance;
    }

    public List<Transaction> getTransactionHistory() {
        return transactionHistory;
    }

    @Override
    public String toString() {
        return "TransitCardData{" +
                "cardType=" + cardType +
                ", cardNumber='" + cardNumber + '\'' +
                ", balance=" + balance +
                ", transactions=" + (transactionHistory != null ? transactionHistory.size() : 0) +
                '}';
    }
}
