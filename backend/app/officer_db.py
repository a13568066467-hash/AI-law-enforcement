"""Deprecated shim — prefer the domain package path.

Will be removed in a later iteration. This module is an alias of the
canonical implementation (including private helpers).
"""
from __future__ import annotations

import importlib
import sys

_impl = importlib.import_module('app.officers.repository')
sys.modules[__name__] = _impl
