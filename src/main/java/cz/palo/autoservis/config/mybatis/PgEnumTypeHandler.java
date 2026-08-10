package cz.palo.autoservis.config.mybatis;

import cz.palo.autoservis.model.domain.warehouse.DocumentType;
import cz.palo.autoservis.model.domain.warehouse.MovementType;
import cz.palo.autoservis.model.domain.warehouse.ReceiptSource;
import cz.palo.autoservis.model.domain.warehouse.ReceiptStatus;
import cz.palo.autoservis.model.domain.warehouse.StockTakeStatus;
import cz.palo.autoservis.model.domain.warehouse.ReturnReason;
import cz.palo.autoservis.model.enums.*;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.*;

/**
 * Obecný MyBatis type handler pro PostgreSQL ENUM sloupce.
 *
 * <p>Standardní MyBatis {@code EnumTypeHandler} zapisuje VARCHAR, ale PostgreSQL
 * JDBC driver odmítá přiřazení do typovaného ENUM sloupce bez explicitního castu.
 * Tento handler používá {@link PreparedStatement#setObject(int, Object, int)}
 * s {@link Types#OTHER}, díky čemuž driver provede cast automaticky.
 *
 * <p>Konkrétní potomci se registrují přes {@code @MappedTypes} a objevují se
 * automaticky přes {@code type-handlers-package} v {@code application.yaml}.
 *
 * @param <E> typ Java enumu
 */
public abstract class PgEnumTypeHandler<E extends Enum<E>> extends BaseTypeHandler<E> {

    private final Class<E> type;

    protected PgEnumTypeHandler(Class<E> type) {
        this.type = type;
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, E parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setObject(i, parameter.name(), Types.OTHER);
    }

    @Override
    public E getNullableResult(ResultSet rs, String columnName) throws SQLException {
        String value = rs.getString(columnName);
        return value == null ? null : Enum.valueOf(type, value);
    }

    @Override
    public E getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        String value = rs.getString(columnIndex);
        return value == null ? null : Enum.valueOf(type, value);
    }

    @Override
    public E getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        String value = cs.getString(columnIndex);
        return value == null ? null : Enum.valueOf(type, value);
    }

    // =========================================================================
    // Konkrétní handlery — jeden na každý aplikační enum
    // =========================================================================

    @MappedTypes(CustomerType.class)
    public static class CustomerTypeHandler extends PgEnumTypeHandler<CustomerType> {
        public CustomerTypeHandler() { super(CustomerType.class); }
    }

    @MappedTypes(AddressType.class)
    public static class AddressTypeHandler extends PgEnumTypeHandler<AddressType> {
        public AddressTypeHandler() { super(AddressType.class); }
    }

    @MappedTypes(ContactChannel.class)
    public static class ContactChannelHandler extends PgEnumTypeHandler<ContactChannel> {
        public ContactChannelHandler() { super(ContactChannel.class); }
    }

    @MappedTypes(OrderStatus.class)
    public static class OrderStatusHandler extends PgEnumTypeHandler<OrderStatus> {
        public OrderStatusHandler() { super(OrderStatus.class); }
    }

    @MappedTypes(InvoiceStatus.class)
    public static class InvoiceStatusHandler extends PgEnumTypeHandler<InvoiceStatus> {
        public InvoiceStatusHandler() { super(InvoiceStatus.class); }
    }

    @MappedTypes(PaymentMethod.class)
    public static class PaymentMethodHandler extends PgEnumTypeHandler<PaymentMethod> {
        public PaymentMethodHandler() { super(PaymentMethod.class); }
    }

    @MappedTypes(OrderItemType.class)
    public static class OrderItemTypeHandler extends PgEnumTypeHandler<OrderItemType> {
        public OrderItemTypeHandler() { super(OrderItemType.class); }
    }

    @MappedTypes(ReceiptStatus.class)
    public static class ReceiptStatusHandler extends PgEnumTypeHandler<ReceiptStatus> {
        public ReceiptStatusHandler() { super(ReceiptStatus.class); }
    }

    @MappedTypes(StockTakeStatus.class)
    public static class StockTakeStatusHandler extends PgEnumTypeHandler<StockTakeStatus> {
        public StockTakeStatusHandler() { super(StockTakeStatus.class); }
    }

    @MappedTypes(DocumentType.class)
    public static class DocumentTypeHandler extends PgEnumTypeHandler<DocumentType> {
        public DocumentTypeHandler() { super(DocumentType.class); }
    }

    @MappedTypes(ReceiptSource.class)
    public static class ReceiptSourceHandler extends PgEnumTypeHandler<ReceiptSource> {
        public ReceiptSourceHandler() { super(ReceiptSource.class); }
    }

    @MappedTypes(MovementType.class)
    public static class MovementTypeHandler extends PgEnumTypeHandler<MovementType> {
        public MovementTypeHandler() { super(MovementType.class); }
    }

    @MappedTypes(ReturnReason.class)
    public static class ReturnReasonHandler extends PgEnumTypeHandler<ReturnReason> {
        public ReturnReasonHandler() { super(ReturnReason.class); }
    }

    @MappedTypes(MileageSource.class)
    public static class MileageSourceHandler extends PgEnumTypeHandler<MileageSource> {
        public MileageSourceHandler() { super(MileageSource.class); }
    }

    @MappedTypes(InvoicePartyRole.class)
    public static class InvoicePartyRoleHandler extends PgEnumTypeHandler<InvoicePartyRole> {
        public InvoicePartyRoleHandler() { super(InvoicePartyRole.class); }
    }

    @MappedTypes(CashReceiptStatus.class)
    public static class CashReceiptStatusHandler extends PgEnumTypeHandler<CashReceiptStatus> {
        public CashReceiptStatusHandler() { super(CashReceiptStatus.class); }
    }

    @MappedTypes(CashReceiptNumberSource.class)
    public static class CashReceiptNumberSourceHandler extends PgEnumTypeHandler<CashReceiptNumberSource> {
        public CashReceiptNumberSourceHandler() { super(CashReceiptNumberSource.class); }
    }

    @MappedTypes(AppointmentStatus.class)
    public static class AppointmentStatusHandler extends PgEnumTypeHandler<AppointmentStatus> {
        public AppointmentStatusHandler() { super(AppointmentStatus.class); }
    }

    @MappedTypes(AppointmentType.class)
    public static class AppointmentTypeHandler extends PgEnumTypeHandler<AppointmentType> {
        public AppointmentTypeHandler() { super(AppointmentType.class); }
    }

}
