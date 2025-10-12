package com.jervis.ui.utils

import org.springframework.beans.BeansException
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import org.springframework.stereotype.Component

@Component
class ApplicationContextProvider : ApplicationContextAware {
    companion object {
        private var applicationContext: ApplicationContext? = null

        /**
         * Získá instanci beanu podle typu
         */
        fun <T> getBean(beanClass: Class<T>): T? = applicationContext?.getBean(beanClass)
    }

    @Throws(BeansException::class)
    override fun setApplicationContext(appContext: ApplicationContext) {
        applicationContext = appContext
    }
}
