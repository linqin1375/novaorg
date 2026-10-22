'use strict';
const changePassword=location.hash==='#password';
if(changePassword){document.querySelector('#title').textContent='修改当前账号密码';document.querySelector('#hint').textContent='修改后请退出并重新登录。已有其他会话仍需单独退出。';document.querySelector('#username-label').hidden=true;document.querySelector('#username').required=false;document.querySelector('#new-label').hidden=false;document.querySelector('#new-password').required=true;document.querySelector('#submit').textContent='保存新密码';}
document.querySelector('#form').addEventListener('submit',async event=>{
 event.preventDefault();const button=document.querySelector('#submit');button.disabled=true;const error=document.querySelector('#error');error.textContent='';
 try{
  const csrf=await(await fetch('/api/auth/csrf')).json();
  const headers={[csrf.headerName]:csrf.token,'Content-Type':changePassword?'application/json':'application/x-www-form-urlencoded'};
  const body=changePassword?JSON.stringify({currentPassword:document.querySelector('#password').value,newPassword:document.querySelector('#new-password').value}):new URLSearchParams({username:document.querySelector('#username').value,password:document.querySelector('#password').value});
  const response=await fetch(changePassword?'/api/auth/password':'/api/login',{method:'POST',headers,body});
  if(!response.ok)throw Error(changePassword?'修改失败：请核对原密码和新密码长度，或重新登录。':'登录失败：请核对账号和密码。');
  location.href='/';
 }catch(e){error.textContent=e.message;}finally{button.disabled=false;}
});
