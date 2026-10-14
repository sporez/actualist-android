# Third-Party Notices

Actualist for Android is an experimental port of Actualist
(https://github.com/sporez/actualist), which is licensed under the GNU GPLv3.
This project is distributed under the same license (see `LICENSE`). The notices
below apply to the components named and do not replace the GPLv3.

## Actualist

Project: https://github.com/sporez/actualist
License: GNU General Public License v3.0

The Kotlin sources in `core/` are translations of Actualist's Swift sources
(`Actualist/LocalFirst/...`) at the reference commit named in `REFERENCE.md`.

## Actual Budget

Project: https://github.com/actualbudget/actual
License: MIT

Actualist implements Actual Budget-compatible storage and synchronization
behavior. The following notice is retained for any portions derived from the
Actual Budget project:

The starter database schema in `core/src/main/resources/actual/starter-schema.sql`,
the migration list in `core/src/main/resources/actual/migrations.txt`, the default
dashboard definitions in `core/.../server/NewBudget.kt`, the title-case character
tables in `core/.../importer/UpstreamTitleCaseCharacters.kt` and the parity oracle
fixtures under `core/src/test/resources/oracle/` are derived from Actual v26.10.0
(via Actualist's sources and parity harnesses). The CI workflows run the published
`actualbudget/actual-server:26.10.0` container image as a disposable test server; it
is not redistributed here.

Copyright James Long

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE.

## Android Jetpack, Kotlin, kotlinx, OkHttp

Android Jetpack (AndroidX) libraries (including WorkManager and Glance), Kotlin, kotlinx.coroutines,
kotlinx.serialization and OkHttp are used as build dependencies under the
Apache License 2.0. They are fetched by Gradle and not vendored in this
repository.
