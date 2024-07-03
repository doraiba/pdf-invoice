package com.github.pdfinvoice.web;

import com.github.pdfinvoice.parse.CustomInvoiceTextStripper;
import com.github.pdfinvoice.parse.Invoice;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.fdf.FDFDocument;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.InputStream;

@RestController
@RequestMapping("pdf")
public class PdfCtrl {


    @PostMapping(value = "parse", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Invoice> parseInvoice(@RequestPart FilePart file) {
        return DataBufferUtils.join(file.content()).map(DataBuffer::asInputStream)
                .flatMap((inputStream) -> {
                    try (PDDocument document = Loader.loadPDF(IOUtils.toByteArray(inputStream))) {
                        CustomInvoiceTextStripper stripper = new CustomInvoiceTextStripper(document);
                        return Mono.just(stripper.getInvoice());
                    } catch (Exception e) {
                        return Mono.error(e);
                    }
                });
    }
}
