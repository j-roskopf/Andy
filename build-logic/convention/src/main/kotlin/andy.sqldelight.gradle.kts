plugins {
    id("app.cash.sqldelight")
}

sqldelight {
    databases {
        create("AndyDatabase") {
            packageName.set("com.joetr.andy.db")
            generateAsync.set(true)
        }
    }
}
