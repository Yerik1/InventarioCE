package com.example.myapplication;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.content.Intent;
import android.text.InputType;
import android.widget.EditText;

import androidx.activity.result.ActivityResultCaller;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.io.BufferedOutputStream;

public class DataBase {

    public interface Logger {
        void info(String msg);
        void error(String msg);
        void toast(String msg);
    }

    public interface Callback {
        void ok(String msg);
        void fail(String err);
    }

    public interface SelectCallback {
        void onSelected(String PLACA, String DESCRIPCION);
        void onCancel(String reason);
    }

    private final Context context;
    private final ActivityResultCaller caller;
    private final Logger logger;

    private final ActivityResultLauncher<String[]> openXlsxLauncher;
    private final ActivityResultLauncher<String> createXlsxLauncher;

    private List<String> headerNames = null;
    private int pickedIdCol = -1;
    private int pickedDescCol = -1;
    private Workbook sourceWb = null;

    // Abrir XLSX para borrar
    private final ActivityResultLauncher<String[]> pickCurrentInventoryLauncher;

    private final ActivityResultLauncher<String[]> openForDeleteLauncher;

    // Estado temporal para borrar
    private Workbook deleteWb = null;
    private Uri deleteUri = null;

    // Prefs para recordar el inventario actual
    private final android.content.SharedPreferences prefs;
    private static final String PREFS_NAME = "inv_prefs";
    private static final String KEY_CURRENT_INVENTORY_URI = "current_inventory_uri";

    // Modo "borrar por NFC": estamos esperando tag
    private volatile boolean waitingNfcDelete = false;

    private volatile boolean inventorySessionActive = false;


    // Estructura mínima para mostrar opciones de borrado
    private static class RowInfo {
        int rowIndex;
        String id;
        String descripcion;
        RowInfo(int rowIndex, String id, String descripcion) {
            this.rowIndex = rowIndex;
            this.id = id;
            this.descripcion = descripcion;
        }
    }

    public DataBase(ActivityResultCaller caller, Context context, Logger logger) {
        this.caller = caller;
        this.context = context;
        this.logger = logger;
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        openXlsxLauncher = caller.registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                this::onOpenXlsxResult
        );

        createXlsxLauncher = caller.registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
                this::onCreateXlsxResult
        );

        openForDeleteLauncher = caller.registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                this::onOpenForDeleteResult
        );

        pickCurrentInventoryLauncher = caller.registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                this::onPickCurrentInventoryResult
        );
    }

    public void startNuevoInventario() {
        logger.info("Selecciona el archivo XLSX de inventario...");
        openXlsxLauncher.launch(new String[]{
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-excel"
        });
    }

    private void setCurrentInventoryUri(@androidx.annotation.Nullable Uri uri, boolean takePersistable) {
        if (uri == null) {
            prefs.edit().putString(KEY_CURRENT_INVENTORY_URI, "").apply();
            return;
        }
        prefs.edit().putString(KEY_CURRENT_INVENTORY_URI, uri.toString()).apply();
        if (takePersistable) {
            try {
                context.getContentResolver().takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                );
            } catch (Exception ignored) {}
        }
    }

    @androidx.annotation.Nullable
    private Uri getCurrentInventoryUri() {
        String s = prefs.getString(KEY_CURRENT_INVENTORY_URI, "");
        if (s == null || s.isEmpty()) return null;
        return Uri.parse(s);
    }

    private boolean hasCurrentInventory() {
        return getCurrentInventoryUri() != null;
    }

    public void startBorrar() {
        final String[] opciones = {"Desde lista", "Desde NFC"};
        new AlertDialog.Builder(context)
                .setTitle("Borrar elemento")
                .setItems(opciones, (d, which) -> {
                    if (which == 0) {
                        // Lista (flujo que ya tenías)
                        logger.info("Selecciona el archivo XLSX para borrar un elemento...");
                        openForDeleteLauncher.launch(new String[]{
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "application/vnd.ms-excel"
                        });
                    } else {
                        // NFC
                        startBorrarPorNfc();
                    }
                })
                .show();
    }

    public void startBorrarPorNfc() {
        // ¿Tenemos inventario actual?
        if (!hasCurrentInventory()) {
            new AlertDialog.Builder(context)
                    .setTitle("Inventario actual")
                    .setMessage("No hay un inventario actual seleccionado. ¿Deseas elegir uno ahora?")
                    .setPositiveButton("Elegir", (dd, ww) -> {
                        pickCurrentInventoryLauncher.launch(new String[]{
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "application/vnd.ms-excel"
                        });
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
            return;
        }
        waitingNfcDelete = true;
        logger.toast("Acerque el dispositivo al NFC para borrar.");
    }

    public void onNfcIdScanned(String idFromTag) {
        if (!waitingNfcDelete) return; // ignorar si no estamos en modo borrado por NFC
        waitingNfcDelete = false;

        if (idFromTag == null || idFromTag.trim().isEmpty()) {
            logger.error("ID NFC vacío.");
            return;
        }

        Uri invUri = getCurrentInventoryUri();
        if (invUri == null) {
            logger.error("No hay inventario actual.");
            return;
        }

        new Thread(() -> {
            Workbook wb = null;
            try {
                // Abrir inventario actual
                try (InputStream is = context.getContentResolver().openInputStream(invUri)) {
                    wb = WorkbookFactory.create(is);
                }
                Sheet sheet = wb.getNumberOfSheets() > 0 ? wb.getSheetAt(0) : null;
                if (sheet == null) {
                    logger.error("El inventario no tiene hojas.");
                    if (wb != null) wb.close();
                    return;
                }

                // Buscar columnas id y descripcion
                Row header = sheet.getRow(sheet.getFirstRowNum());
                if (header == null) {
                    logger.error("El inventario no tiene encabezados.");
                    if (wb != null) wb.close();
                    return;
                }
                int colId = -1, colDesc = -1;
                short min = header.getFirstCellNum();
                short max = header.getLastCellNum();
                for (int c = min; c < max; c++) {
                    String name = cellToString(header.getCell(c)).trim().toLowerCase();
                    if (name.equals("placa")) colId = c;
                    else if (name.equals("ACTDESCRIPCIONn")) colDesc = c;
                }
                if (colId < 0 || colDesc < 0) {
                    logger.error("Encabezados 'id' y/o 'descripcion' no encontrados.");
                    if (wb != null) wb.close();
                    return;
                }

                // Buscar fila por ID
                int first = sheet.getFirstRowNum();
                int last  = sheet.getLastRowNum();
                int foundRowIdx = -1;
                String foundDesc = "";
                for (int r = first + 1; r <= last; r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    String id = cellToString(row.getCell(colId)).trim();
                    if (idFromTag.trim().equals(id)) {
                        foundRowIdx = r;
                        foundDesc = cellToString(row.getCell(colDesc));
                        break;
                    }
                }

                if (foundRowIdx < 0) {
                    logger.toast("El ID leído (" + idFromTag + ") no pertenece al inventario actual.");
                    if (wb != null) wb.close();
                    return;
                }

                // Confirmación en UI
                final int rowToDelete = foundRowIdx;
                final String descToShow = foundDesc;
                final Workbook wbFinal = wb;
                final Uri invUriFinal = invUri;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    String msg = "¿Eliminar \"" + idFromTag + ": " + descToShow + "\" del inventario actual?";
                    new AlertDialog.Builder(context)
                            .setTitle("Confirmar borrado (NFC)")
                            .setMessage(msg)
                            .setPositiveButton("Eliminar", (d, w) -> performDeleteByUri(invUri, wbFinal, rowToDelete))
                            .setNegativeButton("Cancelar", (d, w) -> {
                                try { if (wbFinal != null) wbFinal.close(); } catch (Exception ignore) {}
                            })
                            .show();
                });

            } catch (Exception e) {
                logger.error("Error al buscar ID en inventario: " + e.getMessage());
                try { if (wb != null) wb.close(); } catch (Exception ignore) {}
            }
        }).start();
    }

    private void performDeleteByUri(Uri fileUri, Workbook wbAlreadyOpen, int rowIdxToDelete) {
        new Thread(() -> {
            Workbook wb = wbAlreadyOpen; // ya abierto
            try {
                Sheet sheet = wb.getSheetAt(0);
                int lastRow = sheet.getLastRowNum();

                if (rowIdxToDelete >= 0 && rowIdxToDelete < lastRow) {
                    sheet.shiftRows(rowIdxToDelete + 1, lastRow, -1);
                }
                Row last = sheet.getRow(lastRow);
                if (last != null) sheet.removeRow(last);

                try (OutputStream os = context.getContentResolver().openOutputStream(fileUri)) {
                    if (os == null) throw new IllegalStateException("OutputStream nulo para URI destino.");
                    wb.write(os);
                    os.flush();
                }
                wb.close();

                logger.toast("Elemento eliminado por NFC.");
            } catch (Exception e) {
                logger.error("Error al borrar/guardar (NFC): " + e.getMessage());
                try { if (wb != null) wb.close(); } catch (Exception ignore) {}
            }
        }).start();
    }

    private void onPickCurrentInventoryResult(Uri uri) {
        if (uri == null) {
            logger.error("No se seleccionó inventario actual.");
            return;
        }
        setCurrentInventoryUri(uri, true);
        logger.toast("Inventario actual establecido.");
        waitingNfcDelete = true;
        logger.info("Acerque el dispositivo al NFC para borrar.");
    }

    private void onOpenXlsxResult(Uri uri) {
        if (uri == null) {
            logger.error("No se seleccionó archivo.");
            return;
        }
        try {
            ContentResolver cr = context.getContentResolver();
            try (InputStream is = cr.openInputStream(uri)) {
                sourceWb = WorkbookFactory.create(is);
            }

            Sheet sheet = sourceWb.getNumberOfSheets() > 0 ? sourceWb.getSheetAt(0) : null;
            if (sheet == null) {
                logger.error("El XLSX no tiene hojas.");
                closeSource();
                return;
            }

            Row header = sheet.getRow(sheet.getFirstRowNum());
            if (header == null) {
                logger.error("No se encontró fila de encabezados.");
                closeSource();
                return;
            }

            headerNames = new ArrayList<>();
            short min = header.getFirstCellNum();
            short max = header.getLastCellNum();
            for (int c = min; c < max; c++) {
                Cell cell = header.getCell(c);
                headerNames.add(cellToString(cell));
            }

            showPickColumnsDialog(headerNames);

        } catch (Exception e) {
            logger.error("Error leyendo XLSX: " + e.getMessage());
            closeSource();
        }
    }

    private void showPickColumnsDialog(List<String> headers) {
        if (headers == null || headers.isEmpty()) {
            logger.error("No hay encabezados para seleccionar columnas.");
            return;
        }

        // Ya no mostramos diálogos: usamos TODAS las columnas del Excel origen.
        logger.info("showPickColumnsDialog: usando todas las columnas, sin selección manual.");
        exportFilteredXlsx();
    }

    private void exportFilteredXlsx() {
        if (sourceWb == null) {
            logger.error("No hay libro origen en memoria.");
            return;
        }
        String time = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String suggestedName = "inventario_" + time + ".xlsx";
        logger.info("exportFilteredXlsx: Elige dónde guardar: " + suggestedName);
        createXlsxLauncher.launch(suggestedName);
    }

    private void onCreateXlsxResult(Uri destUri) {
        if (destUri == null) {
            logger.error("No se seleccionó destino para guardar.");
            return;
        }
        if (sourceWb == null) {
            logger.error("No hay libro origen cargado.");
            return;
        }

        // Ejecutar en segundo plano para evitar ANR/crash
        new Thread(() -> {
            int rowsExported = 0;
            try {
                // Construir el workbook de salida
                Workbook outWb = new XSSFWorkbook();
                Sheet outSheet = outWb.createSheet("Inventario");

                Sheet inSheet = sourceWb.getSheetAt(0);
                int first = inSheet.getFirstRowNum();
                int last  = inSheet.getLastRowNum();

                // === Fila de encabezados del archivo original ===
                Row inHeader = inSheet.getRow(first);
                if (inHeader == null) {
                    logger.error("La hoja origen no tiene fila de encabezados.");
                    outWb.close();
                    return;
                }

                short lastCellNum = inHeader.getLastCellNum(); // puede ser -1
                if (lastCellNum <= 0) {
                    logger.error("La fila de encabezados no tiene celdas válidas.");
                    outWb.close();
                    return;
                }

                int colCount = lastCellNum; // columnas 0..colCount-1

                // === Crear encabezado de salida ===
                Row h = outSheet.createRow(0);
                for (int c = 0; c < colCount; c++) {
                    String headerName;
                    try {
                        headerName = cellToString(inHeader.getCell(c));
                    } catch (Exception ex) {
                        headerName = "";
                    }
                    h.createCell(c).setCellValue(headerName == null ? "" : headerName);
                }

                // Agregar columnas extra al final
                int estadoColIdx     = colCount;
                int registradoColIdx = colCount + 1;
                h.createCell(estadoColIdx).setCellValue("estado");
                h.createCell(registradoColIdx).setCellValue("registrado");

                int outRowIdx = 1;

                // === Copiar filas de datos ===
                for (int r = first + 1; r <= last; r++) {
                    Row inRow = inSheet.getRow(r);
                    if (inRow == null) continue;

                    Row outRow = outSheet.createRow(outRowIdx++);

                    boolean allEmpty = true;

                    // Copiar todas las columnas originales
                    for (int c = 0; c < colCount; c++) {
                        String v;
                        try {
                            v = cellToString(inRow.getCell(c));
                        } catch (Exception ex) {
                            v = "";
                        }
                        if (v == null) v = "";
                        if (!v.isEmpty()) allEmpty = false;
                        outRow.createCell(c).setCellValue(v);
                    }

                    // Si la fila está completamente vacía, la eliminamos
                    if (allEmpty) {
                        outSheet.removeRow(outRow);
                        outRowIdx--;
                        continue;
                    }

                    // Rellenar columnas nuevas
                    outRow.createCell(estadoColIdx).setCellValue("No encontrado");
                    outRow.createCell(registradoColIdx).setCellValue("No registrado"); // para llenar después
                }

                rowsExported = outRowIdx - 1;

                // ⚠ IMPORTANTE: NO usar autoSizeColumn en Android con Apache POI
                // NADA de: outSheet.autoSizeColumn(...);

                // Escribir al SAF
                try (OutputStream os = context.getContentResolver().openOutputStream(destUri)) {
                    if (os == null) throw new IllegalStateException("OutputStream nulo para URI destino.");
                    outWb.write(os);
                    os.flush();
                }
                outWb.close();

                // Verificación post-escritura: consultar tamaño
                long size = -1L;
                try (android.database.Cursor c = context.getContentResolver()
                        .query(destUri, new String[]{android.provider.OpenableColumns.SIZE}, null, null, null)) {
                    if (c != null && c.moveToFirst()) size = c.getLong(0);
                }

                final int fRows = rowsExported;
                final long fSize = size;
                logger.info("Se exportaron " + fRows + " filas. Tamaño archivo: " + fSize + " bytes.");
                logger.toast("Inventario generado (" + fRows + " filas).");

            } catch (SecurityException se) {
                logger.error("Permisos para escribir el archivo no concedidos: " + se.getMessage());
            } catch (Exception e) {
                logger.error("Error al guardar XLSX: " + e.getMessage());
            } finally {
                closeSource();
                headerNames = null;
                pickedIdCol = -1;
                pickedDescCol = -1;
                setCurrentInventoryUri(destUri, true);
            }
        }).start();
    }

    private void closeSource() {
        try { if (sourceWb != null) sourceWb.close(); } catch (Exception ignore) {}
        sourceWb = null;
    }

    private static String cellToString(Cell cell) {
        if (cell == null) return "";
        switch (cell.getCellType()) {
            case STRING:  return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue().toString();
                } else {
                    double v = cell.getNumericCellValue();
                    long lv = (long) v;
                    return (Math.abs(v - lv) < 1e-9) ? Long.toString(lv) : Double.toString(v);
                }
            case BOOLEAN: return Boolean.toString(cell.getBooleanCellValue());
            case FORMULA:
                try { return cell.getStringCellValue(); }
                catch (Exception e) { return Double.toString(cell.getNumericCellValue()); }
            default:      return "";
        }
    }

    private void onOpenForDeleteResult(Uri uri) {
        if (uri == null) {
            logger.error("No se seleccionó archivo para borrar.");
            return;
        }
        deleteUri = uri;

        try {
            try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                deleteWb = WorkbookFactory.create(is);
            }

            Sheet sheet = deleteWb.getNumberOfSheets() > 0 ? deleteWb.getSheetAt(0) : null;
            if (sheet == null) {
                logger.error("El XLSX no tiene hojas.");
                safeCloseDeleteWb();
                return;
            }

            // Encontrar índices de columnas por encabezado (id, descripcion)
            Row header = sheet.getRow(sheet.getFirstRowNum());
            if (header == null) {
                logger.error("No se encontró fila de encabezados.");
                safeCloseDeleteWb();
                return;
            }

            int colId = -1, colDesc = -1;
            short min = header.getFirstCellNum();
            short max = header.getLastCellNum();
            for (int c = min; c < max; c++) {
                String name = cellToString(header.getCell(c)).trim().toLowerCase();
                if (name.equals("placa")) colId = c;
                else if (name.equals("actdescripcion")) colDesc = c;
            }
            if (colId < 0 || colDesc < 0) {
                logger.error("No se encontraron columnas 'id' y 'descripcion' en el encabezado.");
                safeCloseDeleteWb();
                return;
            }

            // Construir lista de filas (sin encabezado)
            List<RowInfo> items = new ArrayList<>();
            int first = sheet.getFirstRowNum();
            int last  = sheet.getLastRowNum();
            for (int r = first + 1; r <= last; r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                String id  = cellToString(row.getCell(colId));
                String ds  = cellToString(row.getCell(colDesc));
                if ((id == null || id.isEmpty()) && (ds == null || ds.isEmpty())) continue;
                items.add(new RowInfo(r, id == null ? "" : id, ds == null ? "" : ds));
            }

            if (items.isEmpty()) {
                logger.toast("No hay registros para borrar.");
                safeCloseDeleteWb();
                return;
            }

            showDeletePickerDialog(items, colId, colDesc);

        } catch (Exception e) {
            logger.error("Error abriendo XLSX para borrar: " + e.getMessage());
            safeCloseDeleteWb();
        }
    }

    private void showDeletePickerDialog(List<RowInfo> items, int colId, int colDesc) {
        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            labels[i] = items.get(i).id + " – " + items.get(i).descripcion;
        }

        final int[] pickedIndex = {-1};

        new AlertDialog.Builder(context)
                .setTitle("Selecciona el elemento a borrar")
                .setSingleChoiceItems(labels, -1, (d, which) -> pickedIndex[0] = which)
                .setPositiveButton("Continuar", (d, w) -> {
                    if (pickedIndex[0] < 0) {
                        logger.toast("Debes seleccionar un elemento.");
                        return;
                    }
                    RowInfo chosen = items.get(pickedIndex[0]);
                    confirmAndDelete(chosen, colId, colDesc);
                })
                .setNegativeButton("Cancelar", (d, w) -> {
                    // cancelar y limpiar
                    safeCloseDeleteWb();
                })
                .show();
    }

    private void confirmAndDelete(RowInfo chosen, int colId, int colDesc) {
        String msg = "¿Eliminar \"" + chosen.id + ": " + chosen.descripcion + "\"?";
        new AlertDialog.Builder(context)
                .setTitle("Confirmar borrado")
                .setMessage(msg)
                .setPositiveButton("Eliminar", (d, w) -> performDelete(chosen))
                .setNegativeButton("Cancelar", (d, w) -> safeCloseDeleteWb())
                .show();
    }

    private void performDelete(RowInfo chosen) {
        if (deleteWb == null || deleteUri == null) {
            logger.error("No hay archivo cargado para borrar.");
            safeCloseDeleteWb();
            return;
        }

        new Thread(() -> {
            try {
                Sheet sheet = deleteWb.getSheetAt(0);

                int lastRow = sheet.getLastRowNum();
                int rowIdx  = chosen.rowIndex;

                // Borrar la fila: mover todas las siguientes una hacia arriba
                if (rowIdx >= 0 && rowIdx < lastRow) {
                    sheet.shiftRows(rowIdx + 1, lastRow, -1);
                }
                Row last = sheet.getRow(lastRow);
                if (last != null) {
                    sheet.removeRow(last);
                }

                // Guardar sobre el mismo URI
                try (OutputStream os = context.getContentResolver().openOutputStream(deleteUri)) {
                    if (os == null) throw new IllegalStateException("OutputStream nulo para URI destino.");
                    deleteWb.write(os);
                    os.flush();
                }
                deleteWb.close();

                // Verificación opcional: tamaño
                long size = -1L;
                try (android.database.Cursor c = context.getContentResolver()
                        .query(deleteUri, new String[]{android.provider.OpenableColumns.SIZE}, null, null, null)) {
                    if (c != null && c.moveToFirst()) size = c.getLong(0);
                }

                logger.toast("Elemento eliminado: " + chosen.id);
                logger.info("Archivo actualizado. Tamaño: " + size + " bytes.");

            } catch (Exception e) {
                logger.error("Error al borrar/guardar: " + e.getMessage());
            } finally {
                safeCloseDeleteWb();
            }
        }).start();
    }

    private void safeCloseDeleteWb() {
        try { if (deleteWb != null) deleteWb.close(); } catch (Exception ignore) {}
        deleteWb = null;
        deleteUri = null;
    }

    public void startInventorySession(boolean resetFirst, Callback cb) {
        // Verificamos inventario actual
        Uri uri = getCurrentInventoryUri();
        if (uri == null) {
            new AlertDialog.Builder(context)
                    .setTitle("Inventario actual")
                    .setMessage("No hay inventario actual. ¿Deseas elegir uno?")
                    .setPositiveButton("Elegir", (dd, ww) -> {
                        pickCurrentInventoryLauncher.launch(new String[]{
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "application/vnd.ms-excel"
                        });
                        // Cuando el usuario elija, no sabemos si quiere reiniciar o no;
                        // por simplicidad: informamos y que vuelva a tocar el flujo.
                        if (cb != null) cb.fail("Selecciona el inventario y vuelve a iniciar.");
                    })
                    .setNegativeButton("Cancelar", (dd, ww) -> {
                        if (cb != null) cb.fail("Operación cancelada.");
                    })
                    .show();
            return;
        }

        if (resetFirst) {
            resetAllEstadosAsync(new Callback() {
                @Override public void ok(String msg) {
                    inventorySessionActive = true;
                    if (cb != null) cb.ok("Inventario reiniciado. " + msg);
                }
                @Override public void fail(String err) {
                    inventorySessionActive = false;
                    if (cb != null) cb.fail(err);
                }
            });
        } else {
            inventorySessionActive = true;
            if (cb != null) cb.ok("Continuando inventario.");
        }
    }

    public void stopInventorySession() {
        inventorySessionActive = false;
    }

    private void resetAllEstadosAsync(Callback cb) {
        Uri uri = getCurrentInventoryUri();
        if (uri == null) { if (cb != null) cb.fail("No hay inventario actual."); return; }

        new Thread(() -> {
            Workbook wb = null;
            try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                wb = WorkbookFactory.create(is);
                Sheet sheet = wb.getNumberOfSheets() > 0 ? wb.getSheetAt(0) : null;
                if (sheet == null) { if (cb != null) cb.fail("El archivo no tiene hojas."); return; }

                // localizar columnas
                Row header = sheet.getRow(sheet.getFirstRowNum());
                if (header == null) { if (cb != null) cb.fail("No se encontró encabezado."); return; }
                int colId = -1, colDesc = -1, colEstado = -1;
                short min = header.getFirstCellNum(), max = header.getLastCellNum();
                for (int c = min; c < max; c++) {
                    String name = cellToString(header.getCell(c)).trim().toLowerCase();
                    if (name.equals("placa")) colId = c;
                    else if (name.equals("actdescripcion")) colDesc = c;
                    else if (name.equals("estado")) colEstado = c;
                }
                // si no hay columna estado, la creamos al final
                if (colEstado < 0) {
                    colEstado = max;
                    header.createCell(colEstado).setCellValue("estado");
                }

                int first = sheet.getFirstRowNum(), last = sheet.getLastRowNum();
                int count = 0;
                for (int r = first + 1; r <= last; r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    Cell ce = row.getCell(colEstado);
                    if (ce == null) ce = row.createCell(colEstado);
                    ce.setCellValue("No encontrado");
                    count++;
                }

                try (OutputStream os = context.getContentResolver().openOutputStream(uri)) {
                    if (os == null) throw new IllegalStateException("OutputStream nulo.");
                    wb.write(os);
                    os.flush();
                }
                wb.close();

                if (cb != null) cb.ok("Estados reiniciados (" + count + " filas).");
            } catch (Exception e) {
                try { if (wb != null) wb.close(); } catch (Exception ignore) {}
                if (cb != null) cb.fail("Error reiniciando: " + e.getMessage());
            }
        }).start();
    }

    public void onInventoryIdScanned(String idFromTag, Callback cb) {
        if (!inventorySessionActive) { if (cb != null) cb.fail("Inventario no está activo."); return; }
        if (idFromTag == null || idFromTag.trim().isEmpty()) { if (cb != null) cb.fail("ID vacío."); return; }

        Uri uri = getCurrentInventoryUri();
        if (uri == null) { if (cb != null) cb.fail("No hay inventario actual."); return; }

        new Thread(() -> {
            Workbook wb = null;
            try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                wb = WorkbookFactory.create(is);
                Sheet sheet = wb.getNumberOfSheets() > 0 ? wb.getSheetAt(0) : null;
                if (sheet == null) { if (cb != null) cb.fail("El archivo no tiene hojas."); return; }

                // localizar columnas
                Row header = sheet.getRow(sheet.getFirstRowNum());
                if (header == null) { if (cb != null) cb.fail("No se encontró encabezado."); return; }
                int colId = -1, colDesc = -1, colEstado = -1;
                short min = header.getFirstCellNum(), max = header.getLastCellNum();
                for (int c = min; c < max; c++) {
                    String name = cellToString(header.getCell(c)).trim().toLowerCase();
                    if (name.equals("placa")) colId = c;
                    else if (name.equals("actdescripcion")) colDesc = c;
                    else if (name.equals("estado")) colEstado = c;
                }
                if (colId < 0 || colDesc < 0) { if (cb != null) cb.fail("Faltan columnas 'id'/'descripcion'."); return; }
                if (colEstado < 0) { // crea 'estado' si no existe
                    colEstado = max;
                    header.createCell(colEstado).setCellValue("estado");
                }

                // buscar por ID
                int first = sheet.getFirstRowNum(), last = sheet.getLastRowNum();
                int foundRowIdx = -1;
                String foundDesc = "";
                for (int r = first + 1; r <= last; r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    String id = cellToString(row.getCell(colId)).trim();
                    if (idFromTag.trim().equals(id)) {
                        foundRowIdx = r;
                        foundDesc = cellToString(row.getCell(colDesc));
                        // marcar encontrado
                        Cell ce = row.getCell(colEstado);
                        if (ce == null) ce = row.createCell(colEstado);
                        ce.setCellValue("Encontrado");
                        break;
                    }
                }

                if (foundRowIdx < 0) {
                    if (cb != null) cb.fail("ID no pertenece a este inventario.");
                    if (wb != null) wb.close();
                    return;
                }

                // guardar
                try (OutputStream os = context.getContentResolver().openOutputStream(uri)) {
                    if (os == null) throw new IllegalStateException("OutputStream nulo.");
                    wb.write(os);
                    os.flush();
                }
                wb.close();

                if (cb != null) cb.ok("Marcado como Encontrado: " + idFromTag + " (" + foundDesc + ")");
            } catch (Exception e) {
                try { if (wb != null) wb.close(); } catch (Exception ignore) {}
                if (cb != null) cb.fail("Error marcando: " + e.getMessage());
            }
        }).start();
    }

    public void addItemToInventory(
            String periodo,
            String mes,
            String placa,
            String serie,
            String identificacion,
            String nombre,
            String actDescripcion,
            String claseDescripcion,
            String ubicacion,
            String codigoCf,
            String descripcionCf,
            Callback cb) {

        Uri uri = getCurrentInventoryUri();
        if (uri == null) {
            if (cb != null) cb.fail("No hay inventario actual.");
            return;
        }

        new Thread(() -> {
            Workbook wb = null;
            try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                wb = WorkbookFactory.create(is);
                Sheet sheet = wb.getNumberOfSheets() > 0 ? wb.getSheetAt(0) : null;
                if (sheet == null) {
                    if (cb != null) cb.fail("El archivo no tiene hojas.");
                    return;
                }

                int firstRow = sheet.getFirstRowNum();
                Row header = sheet.getRow(firstRow);
                if (header == null) {
                    header = sheet.createRow(firstRow);
                }

                // Buscar columnas existentes
                int colPerido          = -1;
                int colMes             = -1;
                int colPlaca           = -1;
                int colSerie           = -1;
                int colIdentificacion  = -1;
                int colNombre          = -1;
                int colActDescripcion  = -1;
                int colClaseDescripcion= -1;
                int colUbicacion       = -1;
                int colCodigoCf        = -1;
                int colDescripcionCf   = -1;
                int colEstado          = -1;
                int colRegistrado      = -1;

                short min = header.getFirstCellNum();
                short max = header.getLastCellNum();
                if (min < 0) min = 0;
                if (max < 0) max = 0;
                int nextCol = max;

                for (int c = min; c < max; c++) {
                    Cell hc = header.getCell(c);
                    String name = hc == null ? "" : cellToString(hc);
                    if (name == null) name = "";
                    String lname = name.trim().toLowerCase();

                    if (lname.equals("perido"))                 colPerido          = c;
                    else if (lname.equals("mes"))               colMes             = c;
                    else if (lname.equals("placa"))             colPlaca           = c;
                    else if (lname.equals("serie"))             colSerie           = c;
                    else if (lname.equals("identificacion"))    colIdentificacion  = c;
                    else if (lname.equals("nombre"))            colNombre          = c;
                    else if (lname.equals("actdescripcion"))    colActDescripcion  = c;
                    else if (lname.equals("clasedescripcion"))  colClaseDescripcion= c;
                    else if (lname.equals("ubicacion"))         colUbicacion       = c;
                    else if (lname.equals("codigocf"))          colCodigoCf        = c;
                    else if (lname.equals("descripcioncf"))     colDescripcionCf   = c;
                    else if (lname.equals("estado"))            colEstado          = c;
                    else if (lname.equals("registrado"))        colRegistrado      = c;
                }

                // Crear columnas que falten (con los encabezados EXACTOS del Excel)
                if (colPerido < 0) {
                    colPerido = nextCol++;
                    header.createCell(colPerido).setCellValue("PERIDO");
                }
                if (colMes < 0) {
                    colMes = nextCol++;
                    header.createCell(colMes).setCellValue("MES");
                }
                if (colPlaca < 0) {
                    colPlaca = nextCol++;
                    header.createCell(colPlaca).setCellValue("PLACA");
                }
                if (colSerie < 0) {
                    colSerie = nextCol++;
                    header.createCell(colSerie).setCellValue("SERIE");
                }
                if (colIdentificacion < 0) {
                    colIdentificacion = nextCol++;
                    header.createCell(colIdentificacion).setCellValue("IDENTIFICACION");
                }
                if (colNombre < 0) {
                    colNombre = nextCol++;
                    header.createCell(colNombre).setCellValue("NOMBRE");
                }
                if (colActDescripcion < 0) {
                    colActDescripcion = nextCol++;
                    header.createCell(colActDescripcion).setCellValue("ACTDESCRIPCION");
                }
                if (colClaseDescripcion < 0) {
                    colClaseDescripcion = nextCol++;
                    header.createCell(colClaseDescripcion).setCellValue("CLASEDESCRIPCION");
                }
                if (colUbicacion < 0) {
                    colUbicacion = nextCol++;
                    header.createCell(colUbicacion).setCellValue("UBICACION");
                }
                if (colCodigoCf < 0) {
                    colCodigoCf = nextCol++;
                    header.createCell(colCodigoCf).setCellValue("CODIGOCF");
                }
                if (colDescripcionCf < 0) {
                    colDescripcionCf = nextCol++;
                    header.createCell(colDescripcionCf).setCellValue("DESCRIPCIONCF");
                }
                if (colEstado < 0) {
                    colEstado = nextCol++;
                    header.createCell(colEstado).setCellValue("estado");
                }
                if (colRegistrado < 0) {
                    colRegistrado = nextCol++;
                    header.createCell(colRegistrado).setCellValue("registrado");
                }

                // Crear nueva fila al final
                int lastRow = sheet.getLastRowNum();
                int newRowIndex = lastRow + 1;
                Row row = sheet.getRow(newRowIndex);
                if (row == null) {
                    row = sheet.createRow(newRowIndex);
                }

                // Escribir valores recibidos
                row.createCell(colPerido).setCellValue(periodo == null ? "" : periodo);
                row.createCell(colMes).setCellValue(mes == null ? "" : mes);
                row.createCell(colPlaca).setCellValue(placa == null ? "" : placa);
                row.createCell(colSerie).setCellValue(serie == null ? "" : serie);
                row.createCell(colIdentificacion).setCellValue(identificacion == null ? "" : identificacion);
                row.createCell(colNombre).setCellValue(nombre == null ? "" : nombre);
                row.createCell(colActDescripcion).setCellValue(actDescripcion == null ? "" : actDescripcion);
                row.createCell(colClaseDescripcion).setCellValue(claseDescripcion == null ? "" : claseDescripcion);
                row.createCell(colUbicacion).setCellValue(ubicacion == null ? "" : ubicacion);
                row.createCell(colCodigoCf).setCellValue(codigoCf == null ? "" : codigoCf);
                row.createCell(colDescripcionCf).setCellValue(descripcionCf == null ? "" : descripcionCf);

                // Valores por defecto para estado / registrado
                row.createCell(colEstado).setCellValue("No encontrado");
                row.createCell(colRegistrado).setCellValue("No registrado");

                // Guardar cambios en el mismo archivo
                try (OutputStream os = context.getContentResolver().openOutputStream(uri, "rwt")) {
                    if (os == null) {
                        throw new IllegalStateException("OutputStream nulo para URI destino.");
                    }
                    wb.write(os);
                    os.flush();
                }

                if (cb != null) cb.ok("Ítem agregado al inventario.");

            } catch (Exception e) {
                if (cb != null) cb.fail("Error al agregar al inventario: " + e.getMessage());
            } finally {
                try { if (wb != null) wb.close(); } catch (Exception ignore) {}
            }
        }).start();
    }

    public void selectIdFromInventory(SelectCallback cb) {
        Uri uri = getCurrentInventoryUri();
        if (uri == null) { cb.onCancel("No hay inventario actual."); return; }

        new Thread(() -> {
            Workbook wb = null;
            try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                wb = WorkbookFactory.create(is);
                Sheet sheet = wb.getNumberOfSheets() > 0 ? wb.getSheetAt(0) : null;
                if (sheet == null) { cb.onCancel("El archivo no tiene hojas."); return; }

                Row header = sheet.getRow(sheet.getFirstRowNum());
                if (header == null) { cb.onCancel("No se encontró encabezado."); return; }

                int colId = -1, colDesc = -1, colReg = -1;
                short min = header.getFirstCellNum(), max = header.getLastCellNum();
                for (int c = min; c < max; c++) {
                    String name = cellToString(header.getCell(c));
                    if (name == null) name = "";
                    name = name.trim().toLowerCase();
                    if (name.equals("placa")) colId = c;
                    else if (name.equals("actdescripcion")) colDesc = c;
                    else if (name.equals("registrado")) colReg = c;
                }
                if (colId < 0 || colDesc < 0) { cb.onCancel("Faltan columnas 'id'/'descripcion'."); return; }
                if (colReg < 0) { cb.onCancel("Falta columna 'registrado'."); return; }

                List<String> labels = new ArrayList<>();
                List<String> ids    = new ArrayList<>();
                List<String> descs  = new ArrayList<>();

                int first = sheet.getFirstRowNum(), last = sheet.getLastRowNum();
                for (int r = first + 1; r <= last; r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;

                    String id  = cellToString(row.getCell(colId));
                    String ds  = cellToString(row.getCell(colDesc));
                    String reg = cellToString(row.getCell(colReg));

                    if (id == null) id = "";
                    if (ds == null) ds = "";
                    if (reg == null) reg = "";

                    // Solo mostrar "No registrado"
                    if (!reg.trim().equalsIgnoreCase("no registrado")) continue;
                    if (id.isEmpty()) continue;

                    labels.add(id + " – " + ds);
                    ids.add(id);
                    descs.add(ds);
                }

                Workbook finalWb = wb;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    if (labels.isEmpty()) {
                        cb.onCancel("No hay elementos 'No registrado' en inventario.");
                        try { finalWb.close(); } catch (Exception ignore) {}
                        return;
                    }

                    final int[] pick = {-1};
                    new AlertDialog.Builder(context)
                            .setTitle("Selecciona ID (No registrado)")
                            .setSingleChoiceItems(labels.toArray(new String[0]), -1,
                                    (d, w) -> pick[0] = w)
                            .setPositiveButton("Usar", (d, w) -> {
                                if (pick[0] < 0) {
                                    cb.onCancel("No se seleccionó ID.");
                                } else {
                                    cb.onSelected(ids.get(pick[0]), descs.get(pick[0]));
                                }
                                try { finalWb.close(); } catch (Exception ignore) {}
                            })
                            .setNegativeButton("Cancelar", (d, w) -> {
                                cb.onCancel("Cancelado.");
                                try { finalWb.close(); } catch (Exception ignore) {}
                            })
                            .show();
                });
            } catch (Exception e) {
                try { if (wb != null) wb.close(); } catch (Exception ignore) {}
                cb.onCancel("Error abriendo inventario: " + e.getMessage());
            }
        }).start();
    }

    public void markItemAsRegistered(String targetId, Callback cb) {
        Uri uri = getCurrentInventoryUri();
        if (uri == null) {
            if (cb != null) cb.fail("No hay inventario actual.");
            return;
        }

        new Thread(() -> {
            Workbook wb = null;
            try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                wb = WorkbookFactory.create(is);
                Sheet sheet = wb.getNumberOfSheets() > 0 ? wb.getSheetAt(0) : null;
                if (sheet == null) {
                    if (cb != null) cb.fail("El archivo no tiene hojas.");
                    return;
                }

                Row header = sheet.getRow(sheet.getFirstRowNum());
                if (header == null) {
                    if (cb != null) cb.fail("No se encontró encabezado.");
                    return;
                }

                int colId = -1, colReg = -1;
                short min = header.getFirstCellNum(), max = header.getLastCellNum();
                for (int c = min; c < max; c++) {
                    String name = cellToString(header.getCell(c));
                    if (name == null) name = "";
                    name = name.trim().toLowerCase();
                    if (name.equals("placa")) colId = c;
                    else if (name.equals("registrado")) colReg = c;
                }
                if (colId < 0) {
                    if (cb != null) cb.fail("Falta columna 'id'.");
                    return;
                }
                if (colReg < 0) {
                    if (cb != null) cb.fail("Falta columna 'registrado'.");
                    return;
                }

                boolean found = false;
                int first = sheet.getFirstRowNum(), last = sheet.getLastRowNum();
                for (int r = first + 1; r <= last; r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;

                    String id = cellToString(row.getCell(colId));
                    if (id == null) id = "";
                    if (id.trim().equals(targetId.trim())) {
                        Cell regCell = row.getCell(colReg);
                        if (regCell == null) regCell = row.createCell(colReg);
                        regCell.setCellValue("Registrado");
                        found = true;
                        break;
                    }
                }

                if (!found) {
                    if (cb != null) cb.fail("No se encontró el ID en el inventario.");
                    return;
                }

                // Guardar cambios en el mismo archivo
                try (OutputStream os = context.getContentResolver()
                        .openOutputStream(uri, "rwt")) {
                    if (os == null) throw new IllegalStateException("OutputStream nulo para URI destino.");
                    wb.write(os);
                    os.flush();
                }

                if (cb != null) cb.ok("Estado actualizado a 'Registrado' para ID " + targetId);

            } catch (Exception e) {
                if (cb != null) cb.fail("Error actualizando inventario: " + e.getMessage());
            } finally {
                try { if (wb != null) wb.close(); } catch (Exception ignore) {}
            }
        }).start();
    }
}
