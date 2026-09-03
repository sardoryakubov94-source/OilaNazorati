document.getElementById('year').textContent=new Date().getFullYear();

// Smoothly close the mobile/desktop FAQ item when another item opens.
document.querySelectorAll('.faq details').forEach(item=>{
  item.addEventListener('toggle',()=>{
    if(item.open){document.querySelectorAll('.faq details').forEach(other=>{if(other!==item)other.open=false})}
  });
});