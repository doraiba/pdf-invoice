package com.github.pdfinvoice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.pdfinvoice.parse.CustomInvoiceTextStripper;
import lombok.SneakyThrows;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@SpringBootTest
class PdfInvoiceApplicationTests {
    ObjectMapper objectMapper = new ObjectMapper();

    {
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    @SneakyThrows
    String toString(Object o) {
        return objectMapper.writeValueAsString(o);
    }

    @Test
    void contextLoads() {
    }

    @Test
    public void testPdf() throws Exception {

        Path dir = Paths.get("test");
        Files.list(dir).filter(e -> e.getFileName().toString().contains(".pdf")).map(path -> {
            try (PDDocument document = Loader.loadPDF(path.toFile())) {
                CustomInvoiceTextStripper pdfTextStripper = new CustomInvoiceTextStripper(document);
                return Pair.of(path.getFileName(), pdfTextStripper.getInvoice());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).forEach(t2 -> {
            System.err.printf("==========%s===========%n", t2.getKey());
            System.err.println(toString(t2.getValue()));
        });


    }

}
