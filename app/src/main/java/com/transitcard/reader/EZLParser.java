package com.transitcard.reader;

import android.nfc.tech.IsoDep;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class EZLParser implements CardParser {
    private static final String TAG = "EZLParser";

    @Override
    public TransitCardData parse(IsoDep isoDep, byte[] cardId) {
        try {
            // Read balance
            int balance = readBalance(isoDep);

            // Read transaction history
            List<Transaction> transactions = readTransactionHistory(isoDep);

            return new TransitCardData(
                    CardType.EZL,
                    bytesToHex(cardId),
                    balance,
                    transactions
            );
        } catch (Exception e) {
            Log.e(TAG, "Error parsing EZL card", e);
            // Return basic info even if detailed parsing fails
            return new TransitCardData(
                    CardType.EZL,
                    bytesToHex(cardId),
                    0,
                    new ArrayList<>()
            );
        }
    }

    private int readBalance(IsoDep isoDep) {
        try {
            // Read balance from file
            // Command: Read Record (File 04, Record 00)
            byte[] command = new byte[]{
                    (byte) 0x90, (byte) 0xB0, // CLA, INS
                    (byte) 0x00, (byte) 0x00, // P1, P2
                    (byte) 0x04                  // Le
            };

            byte[] response = isoDep.transceive(command);
            if (response.length >= 6) {
                // Balance is typically stored in bytes 0-3 (big-endian)
                return ByteBuffer.wrap(response, 0, 4).getInt();
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
            // Read transaction records (up to 20 records)
            for (int recordNum = 0; recordNum < 20; recordNum++) {
                byte[] command = new byte[]{
                        (byte) 0x90, (byte) 0xB2, // CLA, INS (Read Record)
                        (byte) recordNum, (byte) 0x00, // P1 (record number), P2
                        (byte) 0x10                  // Le (16 bytes)
                };

                byte[] response = isoDep.transceive(command);
                if (response.length >= 18) { // 16 bytes data + 2 bytes SW
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

        // Return last 10 transactions
        return transactions.size() > 10 ? transactions.subList(0, 10) : transactions;
    }

    private Transaction parseTransactionRecord(byte[] data) {
        try {
            // Parse transaction data (this is simplified)
            // Real format would need proper decoding based on EZL specifications
            int amount = ByteBuffer.wrap(data, 4, 4).getInt();
            int balanceAfter = ByteBuffer.wrap(data, 8, 4).getInt();

            return new Transaction(
                    "2024-01-01", // Would parse from actual data
                    "이즐 사용처",     // Would parse from actual data
                    amount,
                    balanceAfter,
                    amount < 0 ? TransactionType.USE : TransactionType.CHARGE
            );
        } catch (Exception e) {
            Log.e(TAG, "Error parsing transaction record", e);
            return null;
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02X", b));
        }
        return result.toString();
    }
}
