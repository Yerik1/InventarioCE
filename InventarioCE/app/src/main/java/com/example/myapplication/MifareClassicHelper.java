package com.example.myapplication;

import android.nfc.Tag;
import android.nfc.tech.MifareClassic;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class MifareClassicHelper {

    // --------------------------------------------------------------------------------------------
    // Atributos (Campos/Propiedades)
    // --------------------------------------------------------------------------------------------

    /** Descripción: Clave por defecto (0xFF...) usada para autenticar sectores A/B en MIFARE Classic. */
    static final byte[] KEY_DEFAULT = new byte[]{
            (byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF
    };

    // --------------------------------------------------------------------------------------------
    // Funciones públicas
    // --------------------------------------------------------------------------------------------

    /**
     * Entradas:
     *   - tag (Tag): instancia del tag NFC detectado.
     * Salidas:
     *   - (String): nombres de tecnologías del tag en formato legible (p.ej. "Techs: NfcA, MifareClassic").
     * Descripción:
     *   Construye un string con la lista de tecnologías disponibles en el Tag, útil para depurar
     *   y mostrar información en UI.
     */
    static String techsString(Tag tag) {
        // [Sección] Nulos y preparación
        if (tag == null) return "(sin tag)";

        // [Sección] Recorrer la lista de tecnologías y limpiar el nombre (quitar paquete)
        String[] techs = tag.getTechList();
        StringBuilder sb = new StringBuilder("Techs: ");
        for (int i = 0; i < techs.length; i++) {
            String t = techs[i];
            int lastDot = t.lastIndexOf('.');
            sb.append(lastDot >= 0 ? t.substring(lastDot + 1) : t);
            if (i < techs.length - 1) sb.append(", ");
        }
        return sb.toString();
    }

    /**
     * Entradas:
     *   - tag (Tag): tag NFC detectado.
     *   - sectorIndex (int): índice del sector a leer (0..n).
     *   - key (byte[]): clave para autenticar (Key A o B; se intenta A y luego B).
     * Salidas:
     *   - (String): el PRIMER grupo de dígitos contiguos de longitud ≥ 3 hallado en el contenido ASCII
     *               de los bloques de datos del sector (excluye trailer). Devuelve null si no hay match
     *               o si falla la autenticación/lectura.
     * Descripción:
     *   Autentica el sector, lee los bloques de datos (excluyendo el trailer), convierte a ASCII
     *   "imprimible" y retorna el primer número con 3 o más dígitos (útil para extraer IDs como 97175).
     */

    /**
     * Concatena campos numéricos en orden hasta alcanzar minTotalDigits (p.ej. 6).
     * Útil cuando tu ID está “partido” entre la primera y segunda entrada.
     */
    static String readConcatNumericFromSector(Tag tag, int sectorIndex, byte[] key, int maxDigits) {
        MifareClassic mc = MifareClassic.get(tag);
        if (mc == null) return null;

        try {
            mc.connect();
            boolean auth = mc.authenticateSectorWithKeyA(sectorIndex, key);
            if (!auth) auth = mc.authenticateSectorWithKeyB(sectorIndex, key);
            if (!auth) return null;

            int firstBlock = mc.sectorToBlock(sectorIndex);
            int dataBlocks = mc.getBlockCountInSector(sectorIndex) - 1; // sin trailer
            int totalBytes = dataBlocks * MifareClassic.BLOCK_SIZE;

            byte[] all = new byte[totalBytes];
            int p = 0;
            for (int i = 0; i < dataBlocks; i++) {
                byte[] b = mc.readBlock(firstBlock + i);
                System.arraycopy(b, 0, all, p, b.length);
                p += b.length;
            }

            // Encuentra slots (runs de [0-9, espacio, 0x00]) a lo largo del sector
            java.util.List<int[]> slots = findWritableSlots(all, 0);
            if (slots.isEmpty()) return null;

            StringBuilder out = new StringBuilder();
            outer:
            for (int[] s : slots) {
                int start = s[0], len = s[1];
                for (int i = 0; i < len; i++) {
                    byte b = all[start + i];
                    if (b >= '0' && b <= '9') {
                        out.append((char)b);
                        if (out.length() >= maxDigits) break outer;
                    }
                }
            }

            return out.length() > 0 ? out.toString() : null;

        } catch (Exception e) {
            android.util.Log.w("MIFARE_READ_CONCAT", e);
            return null;
        } finally {
            try { mc.close(); } catch (Exception ignored) {}
        }
    }


    /**
     * Entradas:
     *   - tag (Tag): tag NFC detectado.
     *   - sectorIndex (int): índice de sector a inspeccionar.
     *   - regex (String): expresión regular a buscar (p.ej. "\\d{3,}" para números de ≥3 dígitos).
     *   - key (byte[]): clave de autenticación (se intenta A y luego B).
     * Salidas:
     *   - (FieldDetect): metadatos del match (valor, sector, byteStart, byteLength) respecto a la zona
     *                    de datos contiguos del sector. Devuelve null si no hay match o falla autenticación.
     * Descripción:
     *   Autentica el sector y busca un patrón regex en la concatenación ASCII de sus bloques de datos
     *   (excluye trailer). Devuelve la localización del match para luego poder reemplazarlo.
     */
    static FieldDetect detectFieldInSector(Tag tag, int sectorIndex, String regex, byte[] key) {
        MifareClassic mc = MifareClassic.get(tag);
        if (mc == null) return null;

        // Parseo de límites desde el regex (soporta \d{N}, \d{N,}, \d{N,M}); por defecto min=1, max=12
        class Bounds { int min; Integer max; Bounds(int min, Integer max){ this.min=min; this.max=max; } }
        java.util.function.Function<String, Bounds> parseBounds = (String rx) -> {
            java.util.regex.Matcher mm = java.util.regex.Pattern
                    .compile("\\\\d\\{\\s*(\\d+)\\s*(?:,\\s*(\\d*)\\s*)?\\}")
                    .matcher(rx);
            if (mm.find()) {
                int min = Integer.parseInt(mm.group(1));
                Integer max = null;
                if (mm.group(2) != null && !mm.group(2).isEmpty()) {
                    max = Integer.valueOf(mm.group(2));
                }
                return new Bounds(min, max);
            }
            return new Bounds(1, 12); // por defecto
        };

        try {
            mc.connect();
            boolean auth = mc.authenticateSectorWithKeyA(sectorIndex, key);
            if (!auth) auth = mc.authenticateSectorWithKeyB(sectorIndex, key);
            if (!auth) return null;

            // Leer bloques de datos del sector (excluye el trailer)
            int firstBlock = mc.sectorToBlock(sectorIndex);
            int dataBlocks = mc.getBlockCountInSector(sectorIndex) - 1;
            int totalBytes = dataBlocks * MifareClassic.BLOCK_SIZE;

            byte[] all = new byte[totalBytes];
            int p = 0;
            for (int i = 0; i < dataBlocks; i++) {
                byte[] b = mc.readBlock(firstBlock + i);
                System.arraycopy(b, 0, all, p, b.length);
                p += b.length;
            }

            Bounds bounds = parseBounds.apply(regex);
            int minNeeded = Math.max(1, bounds.min);
            Integer maxCap = bounds.max; // puede ser null (sin tope)

            // Recorremos los "slots" escribibles y concatenamos SOLO los dígitos
            java.util.List<int[]> slots = findWritableSlots(all, 0);
            if (slots.isEmpty()) return null;

            StringBuilder sb = new StringBuilder();
            int firstDigitByte = -1;

            outer:
            for (int[] s : slots) {
                int start = s[0], len = s[1];
                for (int i = 0; i < len; i++) {
                    byte b = all[start + i];
                    if (b >= '0' && b <= '9') {
                        if (firstDigitByte < 0) firstDigitByte = start + i; // anclar al primer dígito real
                        sb.append((char)b);
                        if (maxCap != null && sb.length() >= maxCap) break outer;
                    }
                }
            }

            if (sb.length() >= minNeeded && firstDigitByte >= 0) {
                // Si excede max (por seguridad), recortar
                if (maxCap != null && sb.length() > maxCap) {
                    sb.setLength(maxCap);
                }
                return new FieldDetect(sb.toString(), sectorIndex, firstDigitByte, sb.length());
            }

            return null;

        } catch (Exception e) {
            android.util.Log.w("MIFARE_DETECT", e);
            return null;
        } finally {
            try { mc.close(); } catch (Exception ignored) {}
        }
    }

    // --- helpers locales para replaceFieldInSector ---
    private static boolean isAllowedForNumericSlot(byte b) {
        // dígito ASCII, espacio o 0x00 (padding)
        return (b >= '0' && b <= '9') || b == 0x20 || b == 0x00;
    }

    /** Busca "slots" escribibles (runs contiguos de [0-9] / espacio / 0x00) aunque estén vacíos. */
    private static java.util.List<int[]> findWritableSlots(byte[] all, int fromByte) {
        java.util.ArrayList<int[]> slots = new java.util.ArrayList<>();
        int i = Math.max(0, fromByte);
        while (i < all.length) {
            // avanza hasta el próximo byte permitido
            while (i < all.length && !isAllowedForNumericSlot(all[i])) i++;
            if (i >= all.length) break;

            int start = i;
            // extiende mientras sea permitido (esto define el "span" del slot)
            while (i < all.length && isAllowedForNumericSlot(all[i])) i++;
            int span = i - start;

            if (span > 0) slots.add(new int[]{ start, span });
        }
        return slots;
    }

    /**
     * Entradas:
     *   - tag (Tag): tag NFC detectado (mismo que detectó el campo).
     *   - field (FieldDetect): localización del campo a reemplazar (valor, sector, byteStart...).
     *   - newValue (String): nuevo contenido ASCII a escribir (se truncará si no cabe).
     *   - key (byte[]): clave de autenticación (se intenta A y luego B).
     * Salidas:
     *   - (WriteOutcome): resultado de la operación con mensaje para el usuario y detalle técnico.
     * Descripción:
     *   Reemplaza in-place el campo detectado por el nuevo valor, expandiendo hasta donde lo permita
     *   el espacio contiguo compuesto por dígitos y/o padding (espacio 0x20 o 0x00). Escribe todos
     *   los bloques del sector, relee y verifica byte a byte la zona afectada.
     */
    static WriteOutcome replaceFieldInSector(Tag tag, FieldDetect field, String newValue, byte[] key) {
        if (tag == null || field == null) return WriteOutcome.fail("Parámetros inválidos.", "null input");

        MifareClassic mc = MifareClassic.get(tag);
        if (mc == null) return WriteOutcome.fail("Tag no soporta MifareClassic.", "mc==null");

        byte[] newBytes = (newValue == null) ? new byte[0] : newValue.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        try {
            mc.connect();
            boolean auth = mc.authenticateSectorWithKeyA(field.sectorIndex, key);
            if (!auth) auth = mc.authenticateSectorWithKeyB(field.sectorIndex, key);
            if (!auth) return WriteOutcome.fail("No se pudo autenticar el sector para escribir.", "auth failed sector=" + field.sectorIndex);

            int firstBlock = mc.sectorToBlock(field.sectorIndex);
            int dataBlocks = mc.getBlockCountInSector(field.sectorIndex) - 1; // excluye trailer
            int totalBytes = dataBlocks * MifareClassic.BLOCK_SIZE;

            byte[] all = new byte[totalBytes];
            int p = 0;
            for (int i = 0; i < dataBlocks; i++) {
                byte[] b = mc.readBlock(firstBlock + i);
                System.arraycopy(b, 0, all, p, b.length);
                p += b.length;
            }

            // 1) Encontrar slots escribibles desde el byteStart del campo detectado
            java.util.List<int[]> slots = findWritableSlots(all, field.byteStart);
            if (slots.isEmpty()) {
                return WriteOutcome.fail("No hay espacio escribible en el sector (slots no encontrados).", "no writable slots");
            }

            // 2) Escribir newBytes repartiendo y LIMPIAR con espacios el resto
            int writePosInNew = 0;
            boolean anyWritten = false;

            for (int[] s : slots) {
                int start = s[0], span = s[1];
                if (span <= 0) continue;

                // Capacidad real del slot (no cruzar separadores “no permitidos” dentro del slot)
                int cap = Math.min(span, all.length - start);

                // Prepara un buffer del tamaño del slot, relleno con espacios (0x20)
                byte[] slotBuf = new byte[cap];
                java.util.Arrays.fill(slotBuf, (byte)0x20);

                // ¿hay bytes por escribir? si sí, copia el chunk y el resto queda como espacios
                int remaining = newBytes.length - writePosInNew;
                if (remaining > 0) {
                    int chunk = Math.min(cap, remaining);
                    System.arraycopy(newBytes, writePosInNew, slotBuf, 0, chunk);
                    writePosInNew += chunk;
                    anyWritten = true;
                }
                // Si NO hay bytes por escribir, igual dejamos TODO el slot en espacios (limpieza)

                // Vuelca el slot al buffer total
                System.arraycopy(slotBuf, 0, all, start, cap);
            }

            if (!anyWritten && newBytes.length > 0) {
                return WriteOutcome.fail("No hubo espacio para escribir el nuevo valor.", "no capacity");
            }

            // 3) Escribir de regreso los bloques de datos del sector
            for (int i = 0; i < dataBlocks; i++) {
                int blockIndex = firstBlock + i;
                byte[] blockData = new byte[MifareClassic.BLOCK_SIZE];
                System.arraycopy(all, i * MifareClassic.BLOCK_SIZE, blockData, 0, MifareClassic.BLOCK_SIZE);
                mc.writeBlock(blockIndex, blockData);
            }

            // 4) Verificación: releer concatenando dígitos a través de slots y comparar
            //    (usa tu lectura flexible; aquí lo hacemos directo para no depender de UI)
            byte[] back = new byte[totalBytes];
            p = 0;
            for (int i = 0; i < dataBlocks; i++) {
                byte[] b = mc.readBlock(firstBlock + i);
                System.arraycopy(b, 0, back, p, b.length);
                p += b.length;
            }

            // Reconstruir concatenación de dígitos recorriendo slots
            StringBuilder seen = new StringBuilder();
            java.util.List<int[]> verifySlots = findWritableSlots(back, field.byteStart);
            outer:
            for (int[] s : verifySlots) {
                int start = s[0], len = s[1];
                for (int i = 0; i < len; i++) {
                    byte bb = back[start + i];
                    if (bb >= '0' && bb <= '9') {
                        seen.append((char)bb);
                    }
                    // si queremos parar exactamente cuando ya igualamos la longitud del nuevo valor:
                    if (seen.length() >= newBytes.length) break outer;
                }
            }

            String after = seen.toString();
            boolean exact = after.equals(newValue);
            boolean prefix = after.startsWith(newValue); // por si el lector armado recupera más allá (no debería si limpiamos)

            if (!exact) {
                // si quedó de más (arrastre), esta versión ya lo reemplazó por espacios; si falla es por truncamiento
                return WriteOutcome.fail("Verificación falló: lo releído no coincide.", "after=" + after);
            }

            // Si se escribieron menos bytes que la suma de slots (el caso que te interesa),
            // ya quedaron espacios en los demás slots, así que la próxima lectura/detección NO arrastrará basura.
            return WriteOutcome.ok("Número actualizado y slots siguientes limpiados con espacios.");

        } catch (java.io.IOException e) {
            return WriteOutcome.fail("Error E/S durante la escritura. Mantén el tag quieto.", "IOException: " + e.getMessage());
        } catch (Exception e) {
            return WriteOutcome.fail("Fallo inesperado al escribir.", "Exception: " + e.getMessage());
        } finally {
            try { mc.close(); } catch (Exception ignored) {}
        }
    }
}
