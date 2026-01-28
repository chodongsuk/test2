package com.transitcard.reader;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;
import androidx.room.TypeConverters;



@Entity(tableName = "transactions",
        foreignKeys = @ForeignKey(
                entity = CardEntity.class,
                parentColumns = "id",
                childColumns = "cardId",
                onDelete = ForeignKey.CASCADE
        ),
        indices = {@Index("cardId")})
public class Transaction {
    @PrimaryKey(autoGenerate = true)
    private int id;

    private int cardId;  // 어느 카드의 거래인지

    private String date;
    private String location;
    private int amount;
    private int balanceAfter;

    @TypeConverters(TransactionTypeConverter.class)
    private TransactionType transactionType;

    private long timestamp;  // 저장 시간

    // 빈 생성자 (Room 필수)
    public Transaction() {
        this.timestamp = System.currentTimeMillis();
    }

    // 기존 생성자 (NFC 읽기용)
    public Transaction(String date, String location, int amount, int balanceAfter, TransactionType transactionType) {
        this.date = date;
        this.location = location;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.transactionType = transactionType;
        this.timestamp = System.currentTimeMillis();
    }

    // Getters and Setters (Room에서 필요)
    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getCardId() {
        return cardId;
    }

    public void setCardId(int cardId) {
        this.cardId = cardId;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public int getAmount() {
        return amount;
    }

    public void setAmount(int amount) {
        this.amount = amount;
    }

    public int getBalanceAfter() {
        return balanceAfter;
    }

    public void setBalanceAfter(int balanceAfter) {
        this.balanceAfter = balanceAfter;
    }

    public TransactionType getTransactionType() {
        return transactionType;
    }

    public void setTransactionType(TransactionType transactionType) {
        this.transactionType = transactionType;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}