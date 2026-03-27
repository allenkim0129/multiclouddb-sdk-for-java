# Changelog — hyperscaledb-api

All notable changes to the `hyperscaledb-api` module will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this module adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Portable contract types (`HyperscaleDbClient`, `HyperscaleDbProviderAdapter`,
  `HyperscaleDbProviderClient`) and query expression model
- SPI contracts consolidated from former `hyperscaledb-spi` module ([#39])
- Diagnostic support for provider operations

[#39]: https://github.com/microsoft/hyperscale-db-sdk-for-java/pull/39
