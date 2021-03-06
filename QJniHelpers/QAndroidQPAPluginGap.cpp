/*
  QJniHelpers library

  Authors:
  Sergey A. Galin <sergey.galin@gmail.com>

  Distrbuted under The BSD License

  Copyright (c) 2014, DoubleGIS, LLC.
  All rights reserved.

  Redistribution and use in source and binary forms, with or without
  modification, are permitted provided that the following conditions are met:

  * Redistributions of source code must retain the above copyright notice,
	this list of conditions and the following disclaimer.
  * Redistributions in binary form must reproduce the above copyright notice,
	this list of conditions and the following disclaimer in the documentation
	and/or other materials provided with the distribution.
  * Neither the name of the DoubleGIS, LLC nor the names of its contributors
	may be used to endorse or promote products derived from this software
	without specific prior written permission.

  THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
  AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
  THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
  ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS
  BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
  CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
  SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
  INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
  CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
  ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF
  THE POSSIBILITY OF SUCH DAMAGE.
*/
#include <QtCore/qconfig.h>
#include <QtCore/QDebug>
#include <QtCore/QScopedPointer>
#include "QAndroidQPAPluginGap.h"

#if QT_VERSION < 0x050000 && defined(QJNIHELPERS_GRYM)
	#define QPA_QT4GRYM
#elif QT_VERSION >= 0x050000
	#define QPA_QT5
#else
	#error "Unimplemented QPA case"
#endif

#if defined(QPA_QT5)
	#include <QtAndroidExtras/QtAndroidExtras>
#endif

#include "QAndroidQPAPluginGap.h"

#if defined(Q_OS_ANDROID)

#if defined(QPA_QT4GRYM)
	// Exported from QtAndroidCore
	extern JavaVM * qt_android_get_java_vm();
#elif QT_VERSION >= 0x050000
	#include <QtAndroidExtras/QAndroidJniEnvironment>
#else
	#error "Unimplemented QPA case"
#endif

#if defined(QPA_QT4GRYM)
	static const char * const c_activity_getter_class_name = "org/qt/core/QtApplicationBase";
	static const char * const c_activity_getter_method_name = "getActivityStatic";
	// It is OK to return QtActivityBase as it's a descendant of Activity and
	// Java will handle the type casting.
	static const char * const c_activity_getter_result_name = "org/qt/core/QtActivityBase";
#elif defined(QPA_QT5)
	//! \todo If we make another plugin for Qt 5, this place might need an update!
	static const char * const c_activity_getter_class_name = "org/qtproject/qt5/android/QtNative";
	static const char * const c_activity_getter_method_name = "activity";
	static const char * const c_activity_getter_result_name = "android/app/Activity";
#else
	#error "Unimplemented QPA case"
#endif

namespace QAndroidQPAPluginGap {

QAndroidSpecificJniException::QAndroidSpecificJniException(const char * message)
	: QJniBaseException(message? message: "Android-specific JNI exception.")
{
}

JavaVM * detectJavaVM()
{
	#if defined(QPA_QT4GRYM)
		return qt_android_get_java_vm();
	#elif defined(QPA_QT5)
		return QAndroidJniEnvironment::javaVM();
	#else
		#error "Unimplemented QPA case"
	#endif
}

jobject JNICALL getActivity(JNIEnv *, jobject)
{
	QJniClass theclass(c_activity_getter_class_name);
	if (!theclass)
	{
		throw QAndroidSpecificJniException("QAndroid: Activity retriever class could not be accessed.");
	}
	QScopedPointer<QJniObject> activity(theclass.callStaticObject(c_activity_getter_method_name, c_activity_getter_result_name));
	if (!activity)
	{
		throw QAndroidSpecificJniException("QAndroid: Failed to get Activity object.");
	}
	if (!activity->jObject())
	{
		throw QAndroidSpecificJniException("QAndroid: Java instance of the Activity is 0.");
	}
	return QJniEnvPtr().env()->NewLocalRef(activity->jObject());
}

static QScopedPointer<QJniObject> custom_context_;

void setCustomContext(jobject context)
{
	if (context)
	{
		custom_context_.reset(new QJniObject(context, true));
	}
	else
	{
		custom_context_.reset();
	}
}

jobject JNICALL getCustomContext(JNIEnv *, jobject)
{
	if (custom_context_)
	{
		return custom_context_->jObject();
	}
	return 0;
}

bool customContextSet()
{
	return (custom_context_)? true: false;
}

jobject JNICALL getCurrentContext(JNIEnv * env, jobject)
{
	if (jobject ret = getCustomContext())
	{
		QJniEnvPtr jep(env);
		return jep.env()->NewLocalRef(ret);
	}
	return getActivity();
}

Context::Context():
	QJniObject(getCurrentContext(), true)
{
}

void preloadJavaClass(const char * class_name)
{
	// Directly calling to Java_ru_dublgis_qjnihelpers_ClassLoader_nativeJNIPreloadClass seems to be
	// not working properly in some situations (???)
	// Also, it is nicer to have a single function to preload classed and single path to call it.
	// So, let's call it through Java.
	// qDebug()<<__PRETTY_FUNCTION__<<class_name;
	QJniEnvPtr jep;
	if (jep.isClassPreloaded(class_name))
	{
		// qDebug()<<"Class already pre-loaded:"<<class_name;
		return;
	}
	// qDebug()<<"Pre-loading:"<<class_name;

	static const char * const c_class_name = "ru/dublgis/qjnihelpers/ClassLoader";
	static const char * const c_method_name = "callJNIPreloadClass";
	#if defined(QPA_QT4GRYM)
		QJniClass(c_class_name).callStaticVoid(c_method_name, class_name);
	#elif defined(QPA_QT5)
		QAndroidJniObject::callStaticMethod<void>(c_class_name, c_method_name, "(Ljava/lang/String;)V",
			QJniLocalRef(jep, class_name).jObject());
	#endif
}

void preloadJavaClasses()
{
	preloadJavaClass(c_activity_getter_class_name);
	preloadJavaClass("android/os/Build$VERSION");
}

int apiLevel()
{
	static int level_ = -1;
	if (level_ < 0)
	{
		QJniClass version("android/os/Build$VERSION");
		level_ = version.getStaticIntField("SDK_INT");
	}
	return level_;
}

} // namespace QAndroidQPAPluginGap

// JNI entry points. Must be "C" because the function names should not be mangled.
extern "C" {

/*! This function does the actual pre-loading of a Java class. It can be called either from Java
	via ClassLoader.callJNIPreloadClass() or from C++ main() thread as QAndroidQPAPluginGap.preloadJavaClass(). */
JNIEXPORT void JNICALL Java_ru_dublgis_qjnihelpers_ClassLoader_nativeJNIPreloadClass(JNIEnv * env, jobject, jstring classname)
{
	QJniEnvPtr jep(env);
	QString qclassname = jep.QStringFromJString(classname);
	bool ok = jep.preloadClass(qclassname.toLatin1());
	if (!ok)
	{
		qCritical() << "Failed to preload Java class:" << qclassname;
	}

	// During the first call of this function, we can also pre-load classes for our own use.
	static bool first_call = true;
	if (first_call)
	{
		bool mok = jep.preloadClass(c_activity_getter_class_name);
		if (!mok)
		{
			qCritical() << "Failed to preload Java class:" << qclassname;
		}
		first_call = false;
	}
}

} // extern "C"

#endif // #if defined(Q_OS_ANDROID)
