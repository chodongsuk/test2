package com.transitcard.reader;

import android.nfc.tech.IsoDep;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class EasyCardParser implements CardParser {
    private static final String TAG = "EasyCardParser";

    @Override
    public TransitCardData parse(IsoDep isoDep, byte[] cardId) {
        try {
            // Read balance
            int balance = readBalance(isoDep);

            // Read transaction history
            List<Transaction> transactions = readTransactionHistory(isoDep);

            return new TransitCardData(
                    CardType.EASYCARD,
                    bytesToHex(cardId),
                    balance,
                    transactions
            );
        } catch (Exception e) {
            Log.e(TAG, "Error parsing EasyCard", e);
            // Return basic info even if detailed parsing fails
            return new TransitCardData(
                    CardType.EASYCARD,
                    bytesToHex(cardId),
                    0,
                    new ArrayList<>()
            );
        }
    }

    private int readBalance(IsoDep isoDep) {
        try {
            // EasyCard balance reading command
            // Read balance from purse file
            byte[] command = new byte[]{
                    (byte) 0x90, (byte) 0xB0, // CLA, INS (Read Binary)
                    (byte) 0x00, (byte) 0x00, // P1, P2 (offset)
                    (byte) 0x04                  // Le (4 bytes)
            };

            byte[] response = isoDep.transceive(command);
            if (response.length >= 6) {
                // Balance is stored in first 4 bytes (little-endian for EasyCard)
                int balance = ByteBuffer.wrap(response, 0, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
                return balance;
            } else {
                return 0;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading balance", e);
            return 0;
        }
    }

    private List<Transaction> readTransactionHistory(IsoDep isoDep) {
        List<Transaction> transactions = new ArrayList<>();

        try {
            // Read transaction records (up to 10 records)
            for (int recordNum = 1; recordNum <= 10; recordNum++) {
                byte[] command = new byte[]{
                        (byte) 0x90, (byte) 0xB2, // CLA, INS (Read Record)
                        (byte) recordNum, (byte) 0x04, // P1 (record number), P2 (SFI)
                        (byte) 0x18                  // Le (24 bytes)
                };

                byte[] response = isoDep.transceive(command);
                if (response.length >= 26) { // 24 bytes data + 2 bytes SW
                    int sw1 = response[response.length - 2] & 0xFF;
                    int sw2 = response[response.length - 1] & 0xFF;

                    if (sw1 == 0x90 && sw2 == 0x00) {
                        Transaction transaction = parseTransactionRecord(response);
                        if (transaction != null) {
                            transactions.add(transaction);
                        }
                    } else {
                        break; // No more records
                    }
                } else {
                    break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading transaction history", e);
        }

        return transactions;
    }

    private Transaction parseTransactionRecord(byte[] data) {
        try {
            // Parse EasyCard transaction data
            // Note: This is a simplified implementation
            // Actual EasyCard format requires proper decoding based on specifications

            // Transaction type (byte 0)
            int transactionType = data[0] & 0xFF;

            // Amount (bytes 4-7, little-endian)
            int amount = ByteBuffer.wrap(data, 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();

            // Balance after transaction (bytes 8-11, little-endian)
            int balanceAfter = ByteBuffer.wrap(data, 8, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();

            // Date parsing (bytes 12-14 typically contain date info)
            String date = parseDate(data, 12);

            // Location (would need actual lookup table for EasyCard station codes)
            String location = "EasyCard 사용처";

            return new Transaction(
                    date,
                    location,
                    amount,
                    balanceAfter,
                    amount < 0 ? TransactionType.USE : TransactionType.CHARGE
            );
        } catch (Exception e) {
            Log.e(TAG, "Error parsing transaction record", e);
            return null;
        }
    }

    private String parseDate(byte[] data, int offset) {
        try {
            // EasyCard date format parsing
            // Simplified implementation - actual format may vary
            if (offset + 3 <= data.length) {
                int year = 2000 + (data[offset] & 0xFF);
                int month = data[offset + 1] & 0xFF;
                int day = data[offset + 2] & 0xFF;
                return String.format("%04d-%02d-%02d", year, month, day);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing date", e);
        }
        return "알 수 없음";
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02X", b));
        }
        return result.toString();
    }
}
