package com.example.myapplication;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;

import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements NfcAdapter.ReaderCallback {

    private enum Mode { IDLE, READ_ARMED, EDIT_ON_DETECT }

    private NfcAdapter nfcAdapter;
    private TextView tvEstado, tvSalida;
    private Button btnLeer, btnEscribir;

    private volatile Mode mode = Mode.IDLE;
    private volatile String lastReadValue = null; // para prellenar
    private volatile Tag pendingEditTag = null;   // tag detectado al que se escribirá

    private boolean isEmulator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvEstado  = findViewById(R.id.tvEstado);
        tvSalida  = findViewById(R.id.tvSalida);
        btnLeer   = findViewById(R.id.btnLeer);
        btnEscribir = findViewById(R.id.btnEscribir);

        isEmulator =
                android.os.Build.FINGERPRINT.contains("generic")
                        || android.os.Build.FINGERPRINT.startsWith("unknown")
                        || android.os.Build.MODEL.contains("Emulator")
                        || android.os.Build.MODEL.contains("Android SDK built for x86");

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);

        if (nfcAdapter == null) {
            tvEstado.setText(isEmulator
                    ? "Modo emulador: no hay NFC (UI de demo activa)."
                    : "Este dispositivo no tiene NFC. Solo UI.");
        } else if (!nfcAdapter.isEnabled()) {
            tvEstado.setText("NFC desactivado. Actívalo en Ajustes para usar lectura/escritura.");
        } else {
            tvEstado.setText("Elige Leer o Escribir.");
        }

        // === LEER: arma lectura y muestra el valor leído ===
        btnLeer.setOnClickListener(v -> {
            tvSalida.setText("");
            if (nfcAdapter == null || isEmulator) {
                // Simulación
                lastReadValue = "12345678";
                tvSalida.setText("Leído (simulado): " + lastReadValue);
                tvEstado.setText("Lectura simulada.");
                mode = Mode.IDLE;
            } else {
                mode = Mode.READ_ARMED;
                tvEstado.setText("Modo LECTURA armado: acerca el tag para leer.");
            }
        });

        // === ESCRIBIR: detectar tag -> abrir diálogo con valor actual -> grabar ===
        btnEscribir.setOnClickListener(v -> {
            tvSalida.setText("");
            if (nfcAdapter == null || isEmulator) {
                // Simulación completa: "detecta" y edita/graba sin NFC
                String prefill = (lastReadValue == null) ? "" : lastReadValue;
                showEditDialog(prefill, value -> {
                    lastReadValue = value;
                    tvSalida.setText("Grabado (simulado): " + value);
                    tvEstado.setText("Escritura simulada.");
                });
            } else {
                mode = Mode.EDIT_ON_DETECT;
                tvEstado.setText("Acerca el tag para EDITAR su contenido.");
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nfcAdapter != null && !isEmulator) {
            int flags = NfcAdapter.FLAG_READER_NFC_A
                    | NfcAdapter.FLAG_READER_NFC_B
                    | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK;
            nfcAdapter.enableReaderMode(this, this, flags, null);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (nfcAdapter != null && !isEmulator) nfcAdapter.disableReaderMode(this);
    }

    // ==== NFC callback (solo dispositivo real) ====
    @Override
    public void onTagDiscovered(Tag tag) {
        switch (mode) {
            case READ_ARMED: {
                String texto = readFirstTextRecord(tag);
                runOnUiThread(() -> {
                    if (texto == null) {
                        tvSalida.setText("");
                        tvEstado.setText("No se encontró texto NDEF.");
                        lastReadValue = null;
                    } else {
                        lastReadValue = texto;
                        tvSalida.setText("Leído: " + texto);
                        tvEstado.setText("Lectura OK.");
                    }
                    mode = Mode.IDLE;
                });
                break;
            }
            case EDIT_ON_DETECT: {
                // Guardamos el tag, leemos su valor actual y abrimos diálogo
                pendingEditTag = tag;
                String actual = readFirstTextRecord(tag);
                runOnUiThread(() -> {
                    String prefill = (actual == null) ? "" : actual;
                    tvEstado.setText(actual == null
                            ? "Tag sin texto NDEF. Ingresa un nuevo valor para grabar."
                            : "Edita el contenido y confirma para grabar.");
                    showEditDialog(prefill, value -> {
                        boolean ok = writeNdefText(pendingEditTag, value);
                        if (ok) {
                            lastReadValue = value;
                            tvSalida.setText("Grabado: " + value);
                            tvEstado.setText("Escritura OK.");
                        } else {
                            tvEstado.setText("Fallo al escribir. ¿Tag NDEF? ¿No es solo-lectura?");
                        }
                        pendingEditTag = null;
                        mode = Mode.IDLE;
                    });
                });
                break;
            }
            case IDLE:
            default:
                runOnUiThread(() -> toast("Elige Leer o Escribir primero."));
        }
    }

    // ===== Diálogo de edición =====
    private interface ConfirmCallback { void onConfirm(String value); }

    private void showEditDialog(String prefill, ConfirmCallback cb) {
        final EditText input = new EditText(this);
        input.setHint("Número (ej. 88887777)");
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setFilters(new InputFilter[]{ new InputFilter.LengthFilter(32) });
        if (prefill != null) input.setText(prefill);

        new AlertDialog.Builder(this)
                .setTitle("Editar número del tag")
                .setView(input)
                .setPositiveButton("Guardar", (d, w) -> {
                    String val = input.getText().toString().trim();
                    if (val.isEmpty()) {
                        toast("Ingresa un número.");
                        return;
                    }
                    cb.onConfirm(val);
                })
                .setNegativeButton("Cancelar", (d, w) -> {
                    tvEstado.setText("Operación cancelada.");
                    // Si estábamos esperando escribir sobre un tag detectado:
                    pendingEditTag = null;
                    mode = Mode.IDLE;
                })
                .show();
    }

    // ===== Lectura NDEF =====
    private String readFirstTextRecord(Tag tag) {
        try {
            android.nfc.tech.Ndef ndef = android.nfc.tech.Ndef.get(tag);
            if (ndef == null) return null;
            ndef.connect();
            NdefMessage msg = ndef.getNdefMessage();
            ndef.close();
            if (msg == null) return null;

            for (NdefRecord rec : msg.getRecords()) {
                if (rec.getTnf() == NdefRecord.TNF_WELL_KNOWN &&
                        java.util.Arrays.equals(rec.getType(), NdefRecord.RTD_TEXT)) {
                    return parseTextRecord(rec);
                }
                if (rec.getTnf() == NdefRecord.TNF_MIME_MEDIA) {
                    return new String(rec.getPayload(), StandardCharsets.UTF_8);
                }
            }
            return null;
        } catch (Exception e) {
            runOnUiThread(() -> toast("Error leyendo: " + e.getMessage()));
            return null;
        }
    }

    private String parseTextRecord(NdefRecord record) {
        byte[] payload = record.getPayload();
        if (payload == null || payload.length == 0) return null;
        int status = payload[0] & 0xFF;
        boolean utf16 = (status & 0x80) != 0;
        int langLen = status & 0x3F;
        int textStart = 1 + langLen;
        int textLen = payload.length - textStart;
        if (textLen <= 0) return "";
        Charset cs = utf16 ? StandardCharsets.UTF_16 : StandardCharsets.UTF_8;
        return new String(payload, textStart, textLen, cs);
    }

    // ===== Escritura NDEF =====
    private boolean writeNdefText(Tag tag, String text) {
        try {
            if (tag == null) return false;
            NdefRecord textRec = createTextRecord(text, Locale.getDefault(), false); // UTF-8
            NdefMessage msg = new NdefMessage(new NdefRecord[]{ textRec });

            android.nfc.tech.Ndef ndef = android.nfc.tech.Ndef.get(tag);
            if (ndef == null) {
                android.nfc.tech.NdefFormatable fmt = android.nfc.tech.NdefFormatable.get(tag);
                if (fmt != null) {
                    fmt.connect();
                    fmt.format(msg);
                    fmt.close();
                    return true;
                }
                return false;
            }
            ndef.connect();
            if (!ndef.isWritable()) {
                ndef.close();
                runOnUiThread(() -> toast("Tag de solo lectura."));
                return false;
            }
            if (msg.toByteArray().length > ndef.getMaxSize()) {
                ndef.close();
                runOnUiThread(() -> toast("Mensaje demasiado grande para el tag."));
                return false;
            }
            ndef.writeNdefMessage(msg);
            ndef.close();
            return true;

        } catch (Exception e) {
            runOnUiThread(() -> toast("Error escribiendo: " + e.getMessage()));
            return false;
        }
    }

    private NdefRecord createTextRecord(String text, Locale locale, boolean useUtf16) {
        byte[] lang = locale.getLanguage().getBytes(StandardCharsets.US_ASCII);
        Charset enc = useUtf16 ? StandardCharsets.UTF_16 : StandardCharsets.UTF_8;
        byte[] textBytes = text.getBytes(enc);

        int status = lang.length & 0x3F;
        if (useUtf16) status |= 0x80;

        ByteBuffer bb = ByteBuffer.allocate(1 + lang.length + textBytes.length);
        bb.put((byte) status);
        bb.put(lang);
        bb.put(textBytes);
        byte[] payload = bb.array();

        return new NdefRecord(
                NdefRecord.TNF_WELL_KNOWN,
                NdefRecord.RTD_TEXT,
                new byte[0],
                payload
        );
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
