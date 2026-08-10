# Import faktur do skladu — integrační poznámky

Backend endpoint, který přijme PDF fakturu dodavatele, extrahuje z ní data přes
Spring AI a uloží je do tabulek modulu `warehouse` (migrace V18). Tělo requestu je
`multipart/form-data` s jediným polem `file` — vše ostatní (dodavatel, číslo
faktury, číslo objednávky, položky) je v PDF.

## Endpoint

```
POST /api/v1/warehouse/receipts/import
Content-Type: multipart/form-data
  file = <PDF faktura>

201 Created -> GoodsReceiptImportResult (co se uložilo, k ruční kontrole)
409 Conflict -> faktura už je naimportovaná (idempotence)
415 -> není PDF
400 -> chybí / nečitelný soubor
```

Doklad se uloží ve stavu `PENDING_REVIEW`. Potvrzení do `CONFIRMED` řeší samostatná
akce (doporučený další endpoint `PATCH /{id}/confirm`).

## Tok zpracování

```
PDF
 └─ PdfInvoiceExtractionService (Spring AI: PDF -> InvoiceExtractionResult)
 └─ InvoiceReconciliationValidator (sedí součet řádků na total?)
 └─ WarehouseImportServiceImpl  (@Transactional)
        1. dohledej / založ dodavatele (podle IČO)
        2. idempotence: existsInvoice? -> ConflictException
        3. insert goods_receipt (PENDING_REVIEW)
        4. pro každou položku:
             dohledej / založ product (podle SKU)
             insert goods_receipt_item (šarže)
             insert stock_movement (RECEIPT) -> trigger zvedne stav skladu
        5. vrať GoodsReceiptImportResult
```

## Závislost (pom.xml)

Spring Boot 4 vyžaduje Spring AI 2.x. V době psaní je to milestone (GA se očekává
v polovině 2026) — pro produkci jde o vědomé rozhodnutí, případně import držet za
přepínačem funkcí.

```xml
<properties>
    <spring-ai.version>2.0.0-M4</spring-ai.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>${spring-ai.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-anthropic</artifactId>
</dependency>
```

Milestone repozitář (není v Maven Central):

```xml
<repositories>
    <repository>
        <id>spring-milestones</id>
        <url>https://repo.spring.io/milestone</url>
        <snapshots><enabled>false</enabled></snapshots>
    </repository>
</repositories>
```

## Konfigurace (application.yml)

```yaml
spring:
  ai:
    anthropic:
      api-key: ${ANTHROPIC_API_KEY}     # nikdy natvrdo v kódu
      chat:
        options:
          model: claude-sonnet-4-6      # Sonnet = poměr cena/přesnost pro extrakci
          temperature: 0.0              # deterministická extrakce
          max-tokens: 4096

  servlet:
    multipart:
      max-file-size: 10MB               # PDF faktury bývají malé, ale ať je rezerva
      max-request-size: 10MB
```

## Na co si dát pozor při zapojení do projektu

Třída `Media` má v různých verzích Spring AI jiný balíček — ve verzi 1.x je to
`org.springframework.ai.model.Media`, ve 2.x `org.springframework.ai.content.Media`.
Pokud IDE import nenajde, nech ho dohledat správný balíček podle tvé verze.

`@MapperScan` projektu musí pokrývat balíček `cz.palo.autoservis.mapper`, jinak se
`WarehouseImportMapper` nezaregistruje.

Třídy `ConflictException`, `AppUserDetails` (s `getUserId()`) a `PgEnumTypeHandler`
se přebírají z existujícího projektu — endpoint je používá stejně jako moduly
customer a order. Pokud se `PgEnumTypeHandler` jmenuje jinak, uprav cestu v
`WarehouseImportMapper.xml`.

`@PreAuthorize` na endpointu vyžaduje zapnuté method security (běžně už v projektu
je kvůli ostatním modulům).

## Struktura souborů

```
src/main/java/cz/palo/autoservis/
  controller/GoodsReceiptImportController.java
  service/PdfInvoiceExtractionService.java
  service/InvoiceReconciliationValidator.java
  service/WarehouseImportService.java
  service/impl/WarehouseImportServiceImpl.java
  mapper/WarehouseImportMapper.java
  model/dto/warehouse/InvoiceExtractionResult.java
  model/dto/warehouse/GoodsReceiptImportResult.java
  model/domain/warehouse/{Supplier,Product,GoodsReceipt,GoodsReceiptItem,StockMovement}.java
  model/domain/warehouse/{ReceiptStatus,MovementType,ReturnReason}.java
src/main/resources/mapper/warehouse/WarehouseImportMapper.xml
```
