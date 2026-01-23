package com.transitcard.reader;

import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.nfc.tech.NfcA;
import android.util.Log;

public class NFCReader {
    private static final String TAG = "NFCReader";

    public TransitCardData readCard(Tag tag) {
        try {
            byte[] id = tag.getId();
            Log.d(TAG, "Card ID: " + bytesToHex(id));

            // Try IsoDep first (most common for Korean transit cards)
            IsoDep isoDep = IsoDep.get(tag);
            if (isoDep != null) {
                return readIsoDepCard(isoDep, id);
            }

            // Try NfcA
            NfcA nfcA = NfcA.get(tag);
            if (nfcA != null) {
                return readNfcACard(nfcA, id);
            }

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
                case EASYCARD:
                    parser = new EasyCardParser();
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

        byte[] easycardAID = new byte[]{
                (byte) 0xD4, (byte) 0x58, (byte) 0x00, (byte) 0x00,
                (byte) 0x02, (byte) 0x00, (byte) 0x01
        };

        // Try T-money (also used by Seoul City Pass and Korea Tour Card)
        try {
            byte[] response = selectAID(isoDep, tmoneyAID);
            if (response != null && response.length >= 2) {
                int sw1 = response[response.length - 2] & 0xFF;
                int sw2 = response[response.length - 1] & 0xFF;
                if (sw1 == 0x90 && sw2 == 0x00) {
                    // Check if it's Seoul City Pass or Korea Tour Card
                    CardType specificType = detectTmoneyBasedCard(isoDep, cardId);
                    if (specificType != CardType.UNKNOWN) {
                        return specificType;
                    }
                    return CardType.TMONEY;
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "Not T-money based");
        }

        // Try Cashbee
        try {
            byte[] response = selectAID(isoDep, cashbeeAID);
            if (response != null && response.length >= 2) {
                int sw1 = response[response.length - 2] & 0xFF;
                int sw2 = response[response.length - 1] & 0xFF;
                if (sw1 == 0x90 && sw2 == 0x00) {
                    return CardType.CASHBEE;
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "Not Cashbee");
        }

        // Try Rail+
        try {
            byte[] response = selectAID(isoDep, railplusAID);
            if (response != null && response.length >= 2) {
                int sw1 = response[response.length - 2] & 0xFF;
                int sw2 = response[response.length - 1] & 0xFF;
                if (sw1 == 0x90 && sw2 == 0x00) {
                    return CardType.RAILPLUS;
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "Not Rail+");
        }

        // Try M Pass
        try {
            byte[] response = selectAID(isoDep, mpassAID);
            if (response != null && response.length >= 2) {
                int sw1 = response[response.length - 2] & 0xFF;
                int sw2 = response[response.length - 1] & 0xFF;
                if (sw1 == 0x90 && sw2 == 0x00) {
                    return CardType.MPASS;
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "Not M Pass");
        }

        // Try EasyCard
        try {
            byte[] response = selectAID(isoDep, easycardAID);
            if (response != null && response.length >= 2) {
                int sw1 = response[response.length - 2] & 0xFF;
                int sw2 = response[response.length - 1] & 0xFF;
                if (sw1 == 0x90 && sw2 == 0x00) {
                    return CardType.EASYCARD;
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "Not EasyCard");
        }

        // Fallback to ID-based detection
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

    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            result.append(String.format("%02X", b));
        }
        return result.toString();
    }
}
