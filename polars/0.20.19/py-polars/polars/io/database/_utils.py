from __future__ import annotations

import re
import sys
from importlib import import_module
from typing import TYPE_CHECKING, Any

from polars.convert import from_arrow

if TYPE_CHECKING:
    from collections.abc import Coroutine

    if sys.version_info >= (3, 10):
        from typing import TypeAlias
    else:
        from typing_extensions import TypeAlias

    from polars import DataFrame
    from polars.type_aliases import SchemaDict

    try:
        from sqlalchemy.sql.expression import Selectable
    except ImportError:
        Selectable: TypeAlias = Any  # type: ignore[no-redef]


def _run_async(co: Coroutine[Any, Any, Any]) -> Any:
    """Run asynchronous code as if it was synchronous."""
    import asyncio

    from polars._utils.unstable import issue_unstable_warning

    issue_unstable_warning(
        "Use of asynchronous connections is currently considered unstable"
        " and unexpected issues may arise; if this happens, please report them."
    )
    try:
        import nest_asyncio

        nest_asyncio.apply()
    except ModuleNotFoundError as _err:
        msg = (
            "Executing using async drivers requires the `nest_asyncio` package."
            "\n\nPlease run: pip install nest_asyncio"
        )
        raise ModuleNotFoundError(msg) from None

    return asyncio.run(co)


def _read_sql_connectorx(
    query: str | list[str],
    connection_uri: str,
    partition_on: str | None = None,
    partition_range: tuple[int, int] | None = None,
    partition_num: int | None = None,
    protocol: str | None = None,
    schema_overrides: SchemaDict | None = None,
) -> DataFrame:
    try:
        import connectorx as cx
    except ModuleNotFoundError:
        msg = "connectorx is not installed" "\n\nPlease run: pip install connectorx"
        raise ModuleNotFoundError(msg) from None

    try:
        tbl = cx.read_sql(
            conn=connection_uri,
            query=query,
            return_type="arrow2",
            partition_on=partition_on,
            partition_range=partition_range,
            partition_num=partition_num,
            protocol=protocol,
        )
    except BaseException as err:
        # basic sanitisation of /user:pass/ credentials exposed in connectorx errs
        errmsg = re.sub("://[^:]+:[^:]+@", "://***:***@", str(err))
        raise type(err)(errmsg) from err

    return from_arrow(tbl, schema_overrides=schema_overrides)  # type: ignore[return-value]


def _read_sql_adbc(
    query: str,
    connection_uri: str,
    schema_overrides: SchemaDict | None,
    execute_options: dict[str, Any] | None = None,
) -> DataFrame:
    with _open_adbc_connection(connection_uri) as conn, conn.cursor() as cursor:
        cursor.execute(query, **(execute_options or {}))
        tbl = cursor.fetch_arrow_table()
    return from_arrow(tbl, schema_overrides=schema_overrides)  # type: ignore[return-value]


def _open_adbc_connection(connection_uri: str) -> Any:
    driver_name = connection_uri.split(":", 1)[0].lower()

    # map uri prefix to module when not 1:1
    module_suffix_map: dict[str, str] = {
        "postgres": "postgresql",
    }
    try:
        module_suffix = module_suffix_map.get(driver_name, driver_name)
        module_name = f"adbc_driver_{module_suffix}.dbapi"
        import_module(module_name)
        adbc_driver = sys.modules[module_name]
    except ImportError:
        msg = (
            f"ADBC {driver_name} driver not detected"
            f"\n\nIf ADBC supports this database, please run: pip install adbc-driver-{driver_name} pyarrow"
        )
        raise ModuleNotFoundError(msg) from None

    # some backends require the driver name to be stripped from the URI
    if driver_name in ("sqlite", "snowflake"):
        connection_uri = re.sub(f"^{driver_name}:/{{,3}}", "", connection_uri)

    return adbc_driver.connect(connection_uri)
