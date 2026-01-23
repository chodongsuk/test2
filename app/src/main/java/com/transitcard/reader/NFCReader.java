package com.transitcard.reader;

import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.nfc.tech.MifareClassic;
import android.nfc.tech.NfcA;
import android.util.Log;

public class NFCReader {
    private static final String TAG = "NFCReader";

    public TransitCardData readCard(Tag tag) {
        try {
            byte[] id = tag.getId();
            Log.d(TAG, "=== NFC Card Detected ===");
            Log.d(TAG, "Card ID: " + bytesToHex(id));

            // Log available technologies
            String[] techList = tag.getTechList();
            Log.d(TAG, "Available technologies: " + java.util.Arrays.toString(techList));

            // Try IsoDep first (most common for Korean transit cards)
            IsoDep isoDep = IsoDep.get(tag);
            if (isoDep != null) {
                Log.d(TAG, "✓ IsoDep supported - trying IsoDep reader");
                TransitCardData isoDepResult = readIsoDepCard(isoDep, id);
                if (isoDepResult != null && isoDepResult.getCardType() != CardType.UNKNOWN) {
                    return isoDepResult;
                }
                Log.d(TAG, "IsoDep reader failed or returned UNKNOWN - trying other technologies");
            } else {
                Log.d(TAG, "✗ IsoDep NOT supported");
            }

            // Try MifareClassic (used by EZL/Cashbee and other cards)
            MifareClassic mifareClassic = MifareClassic.get(tag);
            if (mifareClassic != null) {
                Log.d(TAG, "✓ MifareClassic supported - using MifareClassic reader");
                return readMifareClassicCard(mifareClassic, id);
            } else {
                Log.d(TAG, "✗ MifareClassic NOT supported");
            }

            // Try NfcA
            NfcA nfcA = NfcA.get(tag);
            if (nfcA != null) {
                Log.d(TAG, "✓ NfcA supported - using NfcA reader");
                return readNfcACard(nfcA, id);
            } else {
                Log.d(TAG, "✗ NfcA NOT supported");
            }

            Log.e(TAG, "✗ No supported NFC technology found for this card");
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Error reading card", e);
            return null;
        }
    }

    private TransitCardData readIsoDepCard(IsoDep isoDep, byte[] cardId) {
        try {
            isoDep.connect();
            isoDep.setTimeout(5000);

            // Detect card type based on card ID and AID
            CardType cardType = detectCardType(cardId, isoDep);

            CardParser parser = null;
            switch (cardType) {
                case TMONEY:
                    parser = new TMoneyParser();
                    break;
                case CASHBEE:
                    parser = new CashbeeParser();
                    break;
                case HANPAY:
                    parser = new HanpayParser();
                    break;
                case RAILPLUS:
                    parser = new RailplusParser();
                    break;
                case MPASS:
                    parser = new MPassParser();
                    break;
                case SEOUL_CITY_PASS:
                    parser = new SeoulCityPassParser();
                    break;
                case KOREA_TOUR_CARD:
                    parser = new KoreaTourCardParser();
                    break;
            }

            TransitCardData result = null;
            if (parser != null) {
                result = parser.parse(isoDep, cardId);
            }

            isoDep.close();
            return result;
        } catch (Exception e) {
            Log.e(TAG, "Error reading IsoDep card", e);
            try {
                isoDep.close();
            } catch (Exception ignored) {
            }
            return null;
        }
    }

    private TransitCardData readNfcACard(NfcA nfcA, byte[] cardId) {
        try {
            nfcA.connect();

            // Create basic card data
            CardType cardType = detectCardTypeFromId(cardId);
            String cardNumber = bytesToHex(cardId);

            nfcA.close();

            return new TransitCardData(cardType, cardNumber, 0);
        } catch (Exception e) {
            Log.e(TAG, "Error reading NfcA card", e);
            try {
                nfcA.close();
            } catch (Exception ignored) {
            }
            return null;
        }
    }

    private CardType detectCardType(byte[] cardId, IsoDep isoDep) {
        Log.d(TAG, "=== Card Detection Started ===");
        Log.d(TAG, "Card ID: " + bytesToHex(cardId));

        // Try to select common Korean transit card AIDs
        byte[] tmoneyAID = new byte[]{
                (byte) 0xD4, (byte) 0x10, (byte) 0x00, (byte) 0x00,
                (byte) 0x03, (byte) 0x00, (byte) 0x01
        };

        byte[] cashbeeAID = new byte[]{
                (byte) 0xD4, (byte) 0x10, (byte) 0x00, (byte) 0x00,
                (byte) 0x03, (byte) 0x00, (byte) 0x02
        };

        byte[] railplusAID = new byte[]{
                (byte) 0xD4, (byte) 0x10, (byte) 0x00, (byte) 0x00,
                (byte) 0x03, (byte) 0x00, (byte) 0x03
        };

        byte[] mpassAID = new byte[]{
                (byte) 0xD4, (byte) 0x10, (byte) 0x00, (byte) 0x00,
                (byte) 0x03, (byte) 0x00, (byte) 0x04
        };

        // Try T-money (also used by Seoul City Pass and Korea Tour Card)
        try {
            Log.d(TAG, "Trying T-money AID: " + bytesToHex(tmoneyAID));
            byte[] response = selectAID(isoDep, tmoneyAID);
            Log.d(TAG, "T-money response: " + (response != null ? bytesToHex(response) : "null"));
            if (response != null && response.length >= 2) {
                int sw1 = response[response.length - 2] & 0xFF;
                int sw2 = response[response.length - 1] & 0xFF;
                Log.d(TAG, "T-money SW: " + Integer.toHexString(sw1) + " " + Integer.toHexString(sw2));
                if (sw1 == 0x90 && sw2 == 0x00) {
                    Log.d(TAG, "✓ Detected as T-money based card");
                    // Check if it's Seoul City Pass or Korea Tour Card
                    CardType specificType = detectTmoneyBasedCard(isoDep, cardId);
                    if (specificType != CardType.UNKNOWN) {
                        return specificType;
                    }
                    return CardType.TMONEY;
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "T-money detection error: " + e.getMessage());
        }

        // Try Cashbee (EZL)
        try {
            Log.d(TAG, "Trying Cashbee/EZL AID: " + bytesToHex(cashbeeAID));
            byte[] response = selectAID(isoDep, cashbeeAID);
            Log.d(TAG, "Cashbee response: " + (response != null ? bytesToHex(response) : "null"));
            if (response != null && response.length >= 2) {
                int sw1 = response[response.length - 2] & 0xFF;
                int sw2 = response[response.length - 1] & 0xFF;
                Log.d(TAG, "Cashbee SW: " + Integer.toHexString(sw1) + " " + Integer.toHexString(sw2));
                if (sw1 == 0x90 && sw2 == 0x00) {
                    Log.d(TAG, "✓ Detected as Cashbee/EZL");
                    return CardType.CASHBEE;
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "Cashbee detection error: " + e.getMessage());
        }

        // Try Rail+
        try {
            Log.d(TAG, "Trying Rail+ AID: " + bytesToHex(railplusAID));
            byte[] response = selectAID(isoDep, railplusAID);
            Log.d(TAG, "Rail+ response: " + (response != null ? bytesToHex(response) : "null"));
            if (response != null && response.length >= 2) {
                int sw1 = response[response.length - 2] & 0xFF;
                int sw2 = response[response.length - 1] & 0xFF;
                Log.d(TAG, "Rail+ SW: " + Integer.toHexString(sw1) + " " + Integer.toHexString(sw2));
                if (sw1 == 0x90 && sw2 == 0x00) {
                    Log.d(TAG, "✓ Detected as Rail+");
                    return CardType.RAILPLUS;
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "Rail+ detection error: " + e.getMessage());
        }

        // Try M Pass
        try {
            Log.d(TAG, "Trying M Pass AID: " + bytesToHex(mpassAID));
            byte[] response = selectAID(isoDep, mpassAID);
            Log.d(TAG, "M Pass response: " + (response != null ? bytesToHex(response) : "null"));
            if (response != null && response.length >= 2) {
                int sw1 = response[response.length - 2] & 0xFF;
                int sw2 = response[response.length - 1] & 0xFF;
                Log.d(TAG, "M Pass SW: " + Integer.toHexString(sw1) + " " + Integer.toHexString(sw2));
                if (sw1 == 0x90 && sw2 == 0x00) {
                    Log.d(TAG, "✓ Detected as M Pass");
                    return CardType.MPASS;
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "M Pass detection error: " + e.getMessage());
        }

        // Fallback to ID-based detection
        Log.e(TAG, "✗ No AID matched - falling back to ID detection");
        return detectCardTypeFromId(cardId);
    }

    private CardType detectTmoneyBasedCard(IsoDep isoDep, byte[] cardId) {
        // Seoul City Pass and Korea Tour Card use T-money platform
        // but have distinctive characteristics

        // This would need actual card testing to implement proper detection
        // For now, return UNKNOWN to default to TMONEY
        return CardType.UNKNOWN;
    }

    private CardType detectCardTypeFromId(byte[] cardId) {
        // This is a simplified detection based on common patterns
        // Real implementation would need more sophisticated detection
        return CardType.UNKNOWN;
    }

    private byte[] selectAID(IsoDep isoDep, byte[] aid) {
        byte[] selectCommand = new byte[6 + aid.length];
        selectCommand[0] = 0x00; // CLA
        selectCommand[1] = (byte) 0xA4; // INS (SELECT)
        selectCommand[2] = 0x04; // P1
        selectCommand[3] = 0x00; // P2
        selectCommand[4] = (byte) aid.length; // Lc
        System.arraycopy(aid, 0, selectCommand, 5, aid.length);
        selectCommand[selectCommand.length - 1] = 0x00; // Le

        try {
            return isoDep.transceive(selectCommand);
        } catch (Exception e) {
            Log.e(TAG, "Error selecting AID", e);
            return null;
        }
    }

    private TransitCardData readMifareClassicCard(MifareClassic mifareClassic, byte[] cardId) {
        try {
            mifareClassic.connect();
            Log.d(TAG, "MifareClassic connected");

            int sectorCount = mifareClassic.getSectorCount();
            int blockCount = mifareClassic.getBlockCount();
            Log.d(TAG, "Card has " + sectorCount + " sectors, " + blockCount + " blocks");

            // Try to authenticate and read sectors to find balance
            // Common keys for transit cards
            byte[][] keys = {
                MifareClassic.KEY_DEFAULT,
                MifareClassic.KEY_MIFARE_APPLICATION_DIRECTORY,
                new byte[]{(byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF},
                new byte[]{(byte)0xA0, (byte)0xA1, (byte)0xA2, (byte)0xA3, (byte)0xA4, (byte)0xA5},
                new byte[]{(byte)0xD3, (byte)0xF7, (byte)0xD3, (byte)0xF7, (byte)0xD3, (byte)0xF7}
            };

            int balance = 0;
            boolean balanceFound = false;

            // Scan through sectors looking for balance data
            for (int sectorIndex = 0; sectorIndex < Math.min(sectorCount, 16); sectorIndex++) {
                boolean authenticated = false;

                // Try each key
                for (byte[] key : keys) {
                    try {
                        if (mifareClassic.authenticateSectorWithKeyA(sectorIndex, key)) {
                            authenticated = true;
                            Log.d(TAG, "✓ Sector " + sectorIndex + " authenticated with key A");
                            break;
                        }
                    } catch (Exception e) {
                        // Try next key
                    }

                    try {
                        if (mifareClassic.authenticateSectorWithKeyB(sectorIndex, key)) {
                            authenticated = true;
                            Log.d(TAG, "✓ Sector " + sectorIndex + " authenticated with key B");
                            break;
                        }
                    } catch (Exception e) {
                        // Try next key
                    }
                }

                if (authenticated) {
                    // Read blocks in this sector
                    int firstBlock = mifareClassic.sectorToBlock(sectorIndex);
                    int blocksInSector = mifareClassic.getBlockCountInSector(sectorIndex);

                    for (int blockOffset = 0; blockOffset < blocksInSector - 1; blockOffset++) {
                        try {
                            int blockIndex = firstBlock + blockOffset;
                            byte[] blockData = mifareClassic.readBlock(blockIndex);

                            // Check if block contains any non-zero data
                            boolean hasData = false;
                            for (byte b : blockData) {
                                if (b != 0) {
                                    hasData = true;
                                    break;
                                }
                            }

                            if (hasData) {
                                Log.d(TAG, "S" + sectorIndex + "B" + blockIndex + ": " + bytesToHex(blockData) + " ✓");
                            } else {
                                Log.d(TAG, "S" + sectorIndex + "B" + blockIndex + ": (empty)");
                            }

                            // Look for balance patterns in multiple formats
                            if (blockData != null && blockData.length >= 4) {
                                // Try big-endian (most common)
                                int balanceBE = ((blockData[0] & 0xFF) << 24) |
                                               ((blockData[1] & 0xFF) << 16) |
                                               ((blockData[2] & 0xFF) << 8) |
                                               (blockData[3] & 0xFF);

                                // Try little-endian
                                int balanceLE = ((blockData[3] & 0xFF) << 24) |
                                               ((blockData[2] & 0xFF) << 16) |
                                               ((blockData[1] & 0xFF) << 8) |
                                               (blockData[0] & 0xFF);

                                // Log any potentially interesting values for debugging
                                if (hasData && (balanceBE > 0 || balanceLE > 0)) {
                                    Log.d(TAG, "  Values: BE=" + balanceBE + ", LE=" + balanceLE);
                                }

                                // Balance should be reasonable (100 to 500,000 won)
                                if (!balanceFound) {
                                    if (balanceBE >= 100 && balanceBE <= 500000) {
                                        balance = balanceBE;
                                        balanceFound = true;
                                        Log.d(TAG, "  ✓ BALANCE FOUND (BE): " + balance + " won");
                                    } else if (balanceLE >= 100 && balanceLE <= 500000) {
                                        balance = balanceLE;
                                        balanceFound = true;
                                        Log.d(TAG, "  ✓ BALANCE FOUND (LE): " + balance + " won");
                                    }
                                }
                            }
                        } catch (Exception e) {
                            Log.d(TAG, "Could not read block " + (firstBlock + blockOffset) + ": " + e.getMessage());
                        }
                    }
                } else {
                    Log.d(TAG, "✗ Could not authenticate sector " + sectorIndex);
                }
            }

            mifareClassic.close();

            Log.d(TAG, "MifareClassic reading complete. Final balance: " + balance + " won");
            return new TransitCardData(
                    CardType.CASHBEE,
                    bytesToHex(cardId),
                    balance
            );

        } catch (Exception e) {
            Log.e(TAG, "Error reading MifareClassic card", e);
            try {
                mifareClassic.close();
            } catch (Exception ignored) {
            }
            return new TransitCardData(
                    CardType.CASHBEE,
                    bytesToHex(cardId),
                    0
            );
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            result.append(String.format("%02X", b));
        }
        return result.toString();
    }
}
