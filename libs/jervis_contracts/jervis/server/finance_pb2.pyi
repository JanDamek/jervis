from jervis.common import types_pb2 as _types_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class GetFinancialSummaryRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "from_date", "to_date")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    FROM_DATE_FIELD_NUMBER: _ClassVar[int]
    TO_DATE_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    from_date: str
    to_date: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., from_date: _Optional[str] = ..., to_date: _Optional[str] = ...) -> None: ...

class GetFinancialSummaryResponse(_message.Message):
    __slots__ = ("total_income", "total_expenses", "total_payments_received", "outstanding_invoices", "overdue_invoices", "overdue_amount", "record_count")
    TOTAL_INCOME_FIELD_NUMBER: _ClassVar[int]
    TOTAL_EXPENSES_FIELD_NUMBER: _ClassVar[int]
    TOTAL_PAYMENTS_RECEIVED_FIELD_NUMBER: _ClassVar[int]
    OUTSTANDING_INVOICES_FIELD_NUMBER: _ClassVar[int]
    OVERDUE_INVOICES_FIELD_NUMBER: _ClassVar[int]
    OVERDUE_AMOUNT_FIELD_NUMBER: _ClassVar[int]
    RECORD_COUNT_FIELD_NUMBER: _ClassVar[int]
    total_income: float
    total_expenses: float
    total_payments_received: float
    outstanding_invoices: int
    overdue_invoices: int
    overdue_amount: float
    record_count: int
    def __init__(self, total_income: _Optional[float] = ..., total_expenses: _Optional[float] = ..., total_payments_received: _Optional[float] = ..., outstanding_invoices: _Optional[int] = ..., overdue_invoices: _Optional[int] = ..., overdue_amount: _Optional[float] = ..., record_count: _Optional[int] = ...) -> None: ...

class ListFinancialRecordsRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "status", "type")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    TYPE_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    status: str
    type: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., status: _Optional[str] = ..., type: _Optional[str] = ...) -> None: ...

class ListFinancialRecordsResponse(_message.Message):
    __slots__ = ("records",)
    RECORDS_FIELD_NUMBER: _ClassVar[int]
    records: _containers.RepeatedCompositeFieldContainer[FinancialRecord]
    def __init__(self, records: _Optional[_Iterable[_Union[FinancialRecord, _Mapping]]] = ...) -> None: ...

class FinancialRecord(_message.Message):
    __slots__ = ("id", "type", "amount", "currency", "amount_czk", "invoice_number", "variable_symbol", "counterparty_name", "status", "issue_date", "due_date", "description")
    ID_FIELD_NUMBER: _ClassVar[int]
    TYPE_FIELD_NUMBER: _ClassVar[int]
    AMOUNT_FIELD_NUMBER: _ClassVar[int]
    CURRENCY_FIELD_NUMBER: _ClassVar[int]
    AMOUNT_CZK_FIELD_NUMBER: _ClassVar[int]
    INVOICE_NUMBER_FIELD_NUMBER: _ClassVar[int]
    VARIABLE_SYMBOL_FIELD_NUMBER: _ClassVar[int]
    COUNTERPARTY_NAME_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    ISSUE_DATE_FIELD_NUMBER: _ClassVar[int]
    DUE_DATE_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    id: str
    type: str
    amount: float
    currency: str
    amount_czk: float
    invoice_number: str
    variable_symbol: str
    counterparty_name: str
    status: str
    issue_date: str
    due_date: str
    description: str
    def __init__(self, id: _Optional[str] = ..., type: _Optional[str] = ..., amount: _Optional[float] = ..., currency: _Optional[str] = ..., amount_czk: _Optional[float] = ..., invoice_number: _Optional[str] = ..., variable_symbol: _Optional[str] = ..., counterparty_name: _Optional[str] = ..., status: _Optional[str] = ..., issue_date: _Optional[str] = ..., due_date: _Optional[str] = ..., description: _Optional[str] = ...) -> None: ...

class CreateFinancialRecordRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "project_id", "type", "amount", "currency", "amount_czk", "vat_rate", "vat_amount", "invoice_number", "variable_symbol", "counterparty_name", "counterparty_ico", "counterparty_account", "issue_date", "due_date", "payment_date", "source_urn", "description")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    PROJECT_ID_FIELD_NUMBER: _ClassVar[int]
    TYPE_FIELD_NUMBER: _ClassVar[int]
    AMOUNT_FIELD_NUMBER: _ClassVar[int]
    CURRENCY_FIELD_NUMBER: _ClassVar[int]
    AMOUNT_CZK_FIELD_NUMBER: _ClassVar[int]
    VAT_RATE_FIELD_NUMBER: _ClassVar[int]
    VAT_AMOUNT_FIELD_NUMBER: _ClassVar[int]
    INVOICE_NUMBER_FIELD_NUMBER: _ClassVar[int]
    VARIABLE_SYMBOL_FIELD_NUMBER: _ClassVar[int]
    COUNTERPARTY_NAME_FIELD_NUMBER: _ClassVar[int]
    COUNTERPARTY_ICO_FIELD_NUMBER: _ClassVar[int]
    COUNTERPARTY_ACCOUNT_FIELD_NUMBER: _ClassVar[int]
    ISSUE_DATE_FIELD_NUMBER: _ClassVar[int]
    DUE_DATE_FIELD_NUMBER: _ClassVar[int]
    PAYMENT_DATE_FIELD_NUMBER: _ClassVar[int]
    SOURCE_URN_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    project_id: str
    type: str
    amount: float
    currency: str
    amount_czk: float
    vat_rate: float
    vat_amount: float
    invoice_number: str
    variable_symbol: str
    counterparty_name: str
    counterparty_ico: str
    counterparty_account: str
    issue_date: str
    due_date: str
    payment_date: str
    source_urn: str
    description: str
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., project_id: _Optional[str] = ..., type: _Optional[str] = ..., amount: _Optional[float] = ..., currency: _Optional[str] = ..., amount_czk: _Optional[float] = ..., vat_rate: _Optional[float] = ..., vat_amount: _Optional[float] = ..., invoice_number: _Optional[str] = ..., variable_symbol: _Optional[str] = ..., counterparty_name: _Optional[str] = ..., counterparty_ico: _Optional[str] = ..., counterparty_account: _Optional[str] = ..., issue_date: _Optional[str] = ..., due_date: _Optional[str] = ..., payment_date: _Optional[str] = ..., source_urn: _Optional[str] = ..., description: _Optional[str] = ...) -> None: ...

class CreateFinancialRecordResponse(_message.Message):
    __slots__ = ("id", "matched")
    ID_FIELD_NUMBER: _ClassVar[int]
    MATCHED_FIELD_NUMBER: _ClassVar[int]
    id: str
    matched: bool
    def __init__(self, id: _Optional[str] = ..., matched: bool = ...) -> None: ...

class ListContractsRequest(_message.Message):
    __slots__ = ("ctx", "client_id", "active_only")
    CTX_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    ACTIVE_ONLY_FIELD_NUMBER: _ClassVar[int]
    ctx: _types_pb2.RequestContext
    client_id: str
    active_only: bool
    def __init__(self, ctx: _Optional[_Union[_types_pb2.RequestContext, _Mapping]] = ..., client_id: _Optional[str] = ..., active_only: bool = ...) -> None: ...

class ListContractsResponse(_message.Message):
    __slots__ = ("contracts",)
    CONTRACTS_FIELD_NUMBER: _ClassVar[int]
    contracts: _containers.RepeatedCompositeFieldContainer[Contract]
    def __init__(self, contracts: _Optional[_Iterable[_Union[Contract, _Mapping]]] = ...) -> None: ...

class Contract(_message.Message):
    __slots__ = ("id", "client_id", "counterparty", "type", "rate", "rate_unit", "currency", "start_date", "end_date", "status")
    ID_FIELD_NUMBER: _ClassVar[int]
    CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    COUNTERPARTY_FIELD_NUMBER: _ClassVar[int]
    TYPE_FIELD_NUMBER: _ClassVar[int]
    RATE_FIELD_NUMBER: _ClassVar[int]
    RATE_UNIT_FIELD_NUMBER: _ClassVar[int]
    CURRENCY_FIELD_NUMBER: _ClassVar[int]
    START_DATE_FIELD_NUMBER: _ClassVar[int]
    END_DATE_FIELD_NUMBER: _ClassVar[int]
    STATUS_FIELD_NUMBER: _ClassVar[int]
    id: str
    client_id: str
    counterparty: str
    type: str
    rate: float
    rate_unit: str
    currency: str
    start_date: str
    end_date: str
    status: str
    def __init__(self, id: _Optional[str] = ..., client_id: _Optional[str] = ..., counterparty: _Optional[str] = ..., type: _Optional[str] = ..., rate: _Optional[float] = ..., rate_unit: _Optional[str] = ..., currency: _Optional[str] = ..., start_date: _Optional[str] = ..., end_date: _Optional[str] = ..., status: _Optional[str] = ...) -> None: ...
