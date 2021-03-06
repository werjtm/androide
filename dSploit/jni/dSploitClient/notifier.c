/* LICENSE
 * 
 */

#include <jni.h>
#include <pthread.h>
#include <errno.h>
#include <string.h>

#include "buffer.h"

#include "log.h"
#include "reader.h"
#include "child.h"
#include "event.h"
#include "controller.h"
#include "cache.h"
#include "handler.h"
#include "control_messages.h"

#include "nmap.h"
#include "ettercap.h"
#include "hydra.h"
#include "arpspoof.h"
#include "tcpdump.h"

#include "notifier.h"

pthread_t notifier_tid = 0;

int on_raw(JNIEnv *env, child_node *c, message *m) {
  char *line;
  jobject event;
  
  if(append_to_buffer(&(c->buffer), m->data, m->head.size)) {
    LOGE("%s: cannot append to buffer", __func__);
    return -1;
  }
  
  while((line=get_line_from_buffer(&(c->buffer)))) {
    event = create_newline_event(env, line);
    if(!event) {
      LOGE("%s: cannot create event", __func__);
    } else if(send_event(env, c, event)) {
      LOGE("%s: cannot send event", __func__);
    }
    
    if(event)
      (*env)->DeleteLocalRef(env, event);
    free(line);
  }
  
  return 0;
}

int on_nmap(JNIEnv *env, child_node *c, message *m) {
  jobject event;
  int ret;
  
  ret = -1;
  
  switch(m->data[0]) {
    case HOP:
      event = create_hop_event(env, m->data);
      break;
    case PORT:
    case SERVICE:
      event = create_port_event(env, m);
      break;
    case OS:
      event = create_os_event(env, m->data);
      break;
    default:
      LOGW("%s: unkown nmap action: %02hhX", __func__, m->data[0]);
      return -1;
  }
  
  if(!event) {
    LOGE("%s: cannot create event", __func__);
  } else if(send_event(env, c, event)) {
    LOGE("%s: cannot send event", __func__);
  } else {
    ret = 0;
  }
  
  if(event)
    (*env)->DeleteLocalRef(env, event);
  
  return ret;
}

int on_ettercap(JNIEnv *env, child_node *c, message *m) {
  jobject event;
  int ret;
  
  ret = -1;
  
  switch(m->data[0]) {
    case READY:
      event = create_ready_event(env, m);
      break;
    case ACCOUNT:
      event = create_account_event(env, m);
      break;
    default:
      LOGW("%s: unkown ettercap action: %02hhX", __func__, m->data[0]);
      return -1;
  }
  
  if(!event) {
    LOGE("%s: cannot create event", __func__);
  } else if(send_event(env, c, event)) {
    LOGE("%s: cannot send event", __func__);
  } else {
    ret = 0;
  }
  
  if(event)
    (*env)->DeleteLocalRef(env, event);
  
  return ret;
}

int on_hydra(JNIEnv *env, child_node *c, message *m) {
  jobject event;
  int ret;
  
  ret = -1;
  
  switch(m->data[0]) {
    case HYDRA_ATTEMPTS:
      event = create_attempts_event(env, m);
      break;
    case HYDRA_WARNING:
    case HYDRA_ERROR:
      event = create_message_event(env, m);
      break;
    case HYDRA_LOGIN:
      event = create_login_event(env, m);
      break;
    default:
      LOGW("%s: unkown hydra action: %02hhX", __func__, m->data[0]);
      return -1;
  }
  
  if(!event) {
    LOGE("%s: cannot create event", __func__);
  } else if(send_event(env, c, event)) {
    LOGE("%s: cannot send event", __func__);
  } else {
    ret = 0;
  }
  
  if(event)
    (*env)->DeleteLocalRef(env, event);
  
  return ret;
}

int on_arpspoof(JNIEnv *env, child_node *c, message *m) {
  jobject event;
  int ret;
  
  ret = -1;
  
  switch(m->data[0]) {
    case ARPSPOOF_ERROR:
      event = create_message_event(env, m);
      break;
    default:
      LOGW("%s: unkown arpspoof action: %02hhX", __func__, m->data[0]);
      return -1;
  }
  
  if(!event) {
    LOGE("%s: cannot create event", __func__);
  } else if(send_event(env, c, event)) {
    LOGE("%s: cannot send event", __func__);
  } else {
    ret = 0;
  }
  
  if(event)
    (*env)->DeleteLocalRef(env, event);
  
  return ret;
}

int on_tcpdump(JNIEnv *env, child_node *c, message *m) {
  jobject event;
  int ret;
  
  ret = -1;
  
  switch(m->data[0]) {
    case TCPDUMP_PACKET:
      event = create_packet_event(env, m);
      break;
    default:
      LOGW("%s: unkown tcpdump action: %02hhX", __func__, m->data[0]);
      return -1;
  }
  
  if(!event) {
    LOGE("%s: cannot create event", __func__);
  } else if(send_event(env, c, event)) {
    LOGE("%s: cannot send event", __func__);
  } else {
    ret = 0;
  }
  
  if(event)
    (*env)->DeleteLocalRef(env, event);
  
  return ret;
}

int on_message(JNIEnv *env, message *m) {
  child_node *c;
  int ret;
  
  ret = -1;
  
  pthread_mutex_lock(&(children.control.mutex));
  for(c=(child_node *) children.list.head;c && c->id != m->head.id;c=(child_node *) c->next);
  
  if(!c) {
    LOGW("%s: child #%u not found", __func__, m->head.id);
    goto exit;
  }
  
  if(!(c->handler->have_stdout)) {
    LOGW("%s: received a message from child #%u, which does not have stdout", __func__, m->head.id);
    goto exit;
  }
  
  if( c->handler == handlers.by_name.blind ) {
    LOGE("%s: received a message from a blind child", __func__);
  } else if( c->handler == handlers.by_name.raw ) {
    ret = on_raw(env, c, m);
  } else if( c->handler == handlers.by_name.nmap ) {
    ret = on_nmap(env, c, m);
  } else if( c->handler == handlers.by_name.ettercap) {
    ret = on_ettercap(env, c, m);
  } else if( c->handler == handlers.by_name.hydra) {
    ret = on_hydra(env, c, m);
  } else if( c->handler == handlers.by_name.arpspoof) {
    ret = on_arpspoof(env, c, m);
  } else if( c->handler == handlers.by_name.tcpdump) {
    ret = on_tcpdump(env, c, m);
  } else {
    LOGW("%s: unkown handler: \"%s\" ( #%u )", __func__, c->handler->name, c->handler->id);
  }
  
  exit:
  
  pthread_mutex_unlock(&(children.control.mutex));
  
  return ret;
  
}

void *notifier(void *arg) {
  msg_node *mn;
  message *m;
  JNIEnv *env;
  jint ret;
  JavaVMAttachArgs args = {
    JNI_VERSION_1_6,
    "notifier",
    NULL
  };
  
  ret = (*jvm)->AttachCurrentThread(jvm, &env, &args);
  
  if(ret != JNI_OK) {
    LOGE("%s: AttachCurrentThread: %d", __func__, args.version); 
    return (void *)-1;
  }
  
  pthread_mutex_lock(&(incoming_messages.control.mutex));
  
  while(incoming_messages.list.head || incoming_messages.control.active) {
    
    while(!(incoming_messages.list.head) && incoming_messages.control.active) {
      pthread_cond_wait(&(incoming_messages.control.cond), &(incoming_messages.control.mutex));
    }
    
    mn = (msg_node *) queue_get(&(incoming_messages.list));
    
    if(!mn)
      continue;
    
    m=mn->msg;
    
    if(IS_CTRL(m)) {
      ret = on_control(env, m);
    } else {
      ret = on_message(env, m);
    }
    if(ret) {
      LOGW("%s: cannot handle this", __func__);
      dump_message(m);
    }
  }
  
  pthread_mutex_unlock(&(incoming_messages.control.mutex));
  
  LOGI("%s: quitting", __func__);
  
  ret = (*jvm)->DetachCurrentThread(jvm);
  
  if(ret != JNI_OK) {
    LOGE("%s: DetachCurrentThread: %d", __func__, ret);
  }
  return 0;
}

int start_notifier() {
  if(notifier_tid)
    return 0;
    
  if(control_activate(&(incoming_messages.control))) {
    LOGE("%s: cannot activate incoming messages queue", __func__);
    return -1;
  }
    
  if(pthread_create(&notifier_tid, NULL, notifier, NULL)) {
    LOGE("%s: pthread_create: %s", __func__, strerror(errno));
    return -1;
  }
  return 0;
}

void stop_notifier() {
  if(notifier_tid) {
    control_deactivate(&(incoming_messages.control));
    pthread_join(notifier_tid, NULL);
    notifier_tid = 0;
  }
}
