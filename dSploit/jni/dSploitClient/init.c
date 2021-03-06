/* LICENSE
 * 
 */

#include <stdio.h>
#include <errno.h>
#include <string.h>
#include <jni.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>

#include "log.h"
#include "init.h"
#include "cache.h"
#include "command.h"
#include "handler.h"
#include "connection.h"
#include "controller.h"
#include "reader.h"
#include "child.h"
#include "msgqueue.h"
#include "auth.h"
#include "logger.h"
#include "fini.h"

#define NUMELEM(a) (sizeof(a)/sizeof(a[0]))

void init_structs() {
  memset(&handlers, 0, sizeof(struct handlers_list));
  memset(&cache, 0, sizeof(struct cache));
  memset(&children, 0, sizeof(struct child_list));
  memset(&incoming_messages, 0, sizeof(struct msgqueue));
  memset(&logged, 0, sizeof(struct login_data));
}

int init_controls() {
  if(control_init(&(children.control)))
    return -1;
  if(control_init(&(incoming_messages.control)))
    return -1;
  if(control_init(&(handlers.control)))
    return -1;
  if(control_init(&(logged.control)))
    return -1;
  return 0;
}


JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* pVm, void* reserved _U_) {
  JNIEnv *env;
  jint ret;
  JNINativeMethod nm[] =  {
    { "LoadHandlers", "()Z", jload_handlers },
    { "StartCommand", "(Ljava/lang/String;Ljava/lang/String;)I", start_command },
    { "Connect", "(Ljava/lang/String;)Z", connect_unix },
    { "Login", "(Ljava/lang/String;Ljava/lang/String;)Z", jlogin },
    { "Disconnect", "()V", disconnect_unix },
    { "Kill", "(II)V", kill_child },
    { "SendTo", "(I[B)Z", send_to_child },
    { "isConnected", "()Z", is_unix_connected },
    { "isAuthenticated", "()Z", is_authenticated },
    { "getHandlers", "()[Ljava/lang/String;", get_handlers },
    { "Shutdown", "()V", request_shutdown },
  };
  
  ret = (*pVm)->GetEnv(pVm, (void **)&env, JNI_VERSION_1_6);
  
  if(ret != JNI_OK) {
    LOGF("%s: GetEnv: %d", __func__, ret);
    goto error;
  }
  
  init_structs();
  set_logger(android_logger);
  
  if(init_controls()) {
    LOGF("%s: cannot init controls", __func__);
    goto error;
  }
  
  if(_init_cache(env)) {
    LOGF("%s: cannot init cache", __func__);
    goto error;
  }
  
  ret = (*env)->RegisterNatives(env, cache.dsploit.core.client.class, nm, NUMELEM(nm));
  
  if(ret != JNI_OK) {
    
    if((*env)->ExceptionCheck(env)) {
      (*env)->ExceptionDescribe(env);
      (*env)->ExceptionClear(env);
    }
    
    LOGF("%s: cannot register native methods: %d", __func__, ret);
    
    goto error;
  }
  
  jvm = pVm;
  
  return JNI_VERSION_1_6;
  
  error:
  
  destroy_controls();
  _free_cache(env);
  
  return -1;
}

